-- V1__init_notification_schema.sql
-- Notification Service — PostgreSQL schema
-- Managed by Flyway. Hibernate ddl-auto: validate.

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ─────────────────────────────────────────────────────────────────────────────
-- NOTIFICATION OUTBOX
-- ─────────────────────────────────────────────────────────────────────────────
-- Implements the Transactional Outbox pattern:
--   1. Consumer writes a PENDING row in the same local transaction as any
--      other state changes — atomicity guaranteed.
--   2. The OutboxScheduler polls this table and dispatches to n8n.
--   3. FOR UPDATE SKIP LOCKED prevents concurrent schedulers from
--      double-processing the same row.

CREATE TABLE notification_outbox (
    id                  UUID            DEFAULT uuid_generate_v4() PRIMARY KEY,
    transaction_id      UUID            NOT NULL,
    event_type          VARCHAR(40)     NOT NULL
                                        CHECK (event_type IN (
                                            'TRANSACTION_CREATED', 'FRAUD_VERDICT')),
    account_id          VARCHAR(100)    NOT NULL,
    recipient_user_id   VARCHAR(100)    NOT NULL,
    alert_severity      VARCHAR(10)     NOT NULL
                                        CHECK (alert_severity IN ('INFO', 'WARNING', 'CRITICAL')),
    payload             JSONB           NOT NULL,

    -- Lifecycle
    status              VARCHAR(15)     NOT NULL DEFAULT 'PENDING'
                                        CHECK (status IN (
                                            'PENDING', 'RETRYING', 'SENT', 'FAILED', 'DEAD')),
    retry_count         SMALLINT        NOT NULL DEFAULT 0,
    max_retries         SMALLINT        NOT NULL DEFAULT 5,
    last_error          VARCHAR(1000),

    -- Timing
    next_retry_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    last_attempted_at   TIMESTAMPTZ,
    sent_at             TIMESTAMPTZ,

    CONSTRAINT outbox_retry_count_valid CHECK (retry_count >= 0),
    CONSTRAINT outbox_retry_not_exceed_max CHECK (retry_count <= max_retries + 1)
);

-- ── Primary scheduler query index ────────────────────────────────────────────
-- Covers: WHERE status IN ('PENDING','FAILED','RETRYING') AND next_retry_at <= NOW()
-- The partial index dramatically shrinks the index size — only unresolved rows included.
CREATE INDEX idx_outbox_retryable
    ON notification_outbox (next_retry_at ASC)
    WHERE status IN ('PENDING', 'FAILED', 'RETRYING');

-- ── Transaction lookup ────────────────────────────────────────────────────────
-- Used by: GET /admin/notifications?transactionId=...
CREATE INDEX idx_outbox_transaction
    ON notification_outbox (transaction_id);

-- ── Severity filter ───────────────────────────────────────────────────────────
-- Allows dashboards to filter CRITICAL unresolved alerts efficiently.
CREATE INDEX idx_outbox_critical_unresolved
    ON notification_outbox (created_at DESC)
    WHERE alert_severity = 'CRITICAL' AND status NOT IN ('SENT', 'DEAD');

-- ── DEAD letter queue view ────────────────────────────────────────────────────
-- Convenience for manual investigation of permanently failed notifications.
CREATE VIEW dead_notifications AS
SELECT id,
       transaction_id,
       event_type,
       account_id,
       alert_severity,
       retry_count,
       last_error,
       last_attempted_at,
       payload
FROM   notification_outbox
WHERE  status = 'DEAD'
ORDER  BY last_attempted_at DESC;

COMMENT ON TABLE notification_outbox IS
    'Transactional outbox for n8n webhook dispatches. '
    'Written atomically with any state change; polled by OutboxScheduler. '
    'SKIP LOCKED ensures safe concurrent processing across multiple instances.';

COMMENT ON COLUMN notification_outbox.payload IS
    'Full NotificationPayload serialised as JSONB. '
    'Stored so the scheduler can retry without re-consuming from Kafka.';

COMMENT ON COLUMN notification_outbox.next_retry_at IS
    'Exponential back-off target: attempt² × backoffBase seconds. '
    'Scheduler only picks up rows where next_retry_at <= NOW().';

-- ─────────────────────────────────────────────────────────────────────────────
-- NOTIFICATION LOG (append-only audit trail of all dispatched notifications)
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE notification_log (
    id                  UUID            DEFAULT uuid_generate_v4() PRIMARY KEY,
    outbox_id           UUID            REFERENCES notification_outbox(id),
    transaction_id      UUID            NOT NULL,
    event_type          VARCHAR(40)     NOT NULL,
    account_id          VARCHAR(100)    NOT NULL,
    alert_severity      VARCHAR(10)     NOT NULL,
    channel             VARCHAR(20)     NOT NULL DEFAULT 'N8N_WEBHOOK',
    http_status_code    SMALLINT,
    dispatched_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notif_log_transaction
    ON notification_log (transaction_id, dispatched_at DESC);

CREATE INDEX idx_notif_log_account
    ON notification_log (account_id, dispatched_at DESC);

COMMENT ON TABLE notification_log IS
    'Immutable audit log written after every successful webhook dispatch. '
    'Separate from outbox so the outbox can be purged without losing history.';
