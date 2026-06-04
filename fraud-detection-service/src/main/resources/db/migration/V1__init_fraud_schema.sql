-- V1__init_fraud_schema.sql
-- Fraud Detection Service — PostgreSQL schema

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "btree_gin";

-- ─────────────────────────────────────────────────────────────────────────────
-- FRAUD RULES — configurable rule registry
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE fraud_rules (
    id              UUID            DEFAULT uuid_generate_v4() PRIMARY KEY,
    rule_name       VARCHAR(80)     NOT NULL UNIQUE,
    rule_type       VARCHAR(40)     NOT NULL
                                    CHECK (rule_type IN (
                                        'VELOCITY', 'AMOUNT_THRESHOLD',
                                        'GEO_ANOMALY', 'OFF_HOURS', 'CUSTOM')),
    parameters      JSONB           NOT NULL DEFAULT '{}',
    score_weight    NUMERIC(4,3)    NOT NULL DEFAULT 0.0
                                    CHECK (score_weight BETWEEN 0 AND 1),
    priority        SMALLINT        NOT NULL DEFAULT 100,
    active          BOOLEAN         NOT NULL DEFAULT TRUE,
    description     TEXT,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_fraud_rules_active
    ON fraud_rules (priority ASC)
    WHERE active = TRUE;

-- Seed the rule registry with default rules
INSERT INTO fraud_rules (rule_name, rule_type, parameters, score_weight, priority, description)
VALUES
    ('VELOCITY_BREACH_PER_MINUTE', 'VELOCITY',
     '{"maxTransactions": 5, "windowSeconds": 60}',
     0.55, 10, 'More than 5 transactions in 60 seconds from one account'),

    ('VELOCITY_BREACH_PER_HOUR',   'VELOCITY',
     '{"maxTransactions": 20, "windowSeconds": 3600}',
     0.30, 20, 'More than 20 transactions in 1 hour from one account'),

    ('HIGH_VALUE_P2P',             'AMOUNT_THRESHOLD',
     '{"threshold": 50000, "transactionType": "PEER_TO_PEER"}',
     0.35, 30, 'P2P transfer exceeds ₹50,000'),

    ('HIGH_VALUE_WITHDRAWAL',      'AMOUNT_THRESHOLD',
     '{"threshold": 100000, "transactionType": "WITHDRAWAL"}',
     0.45, 30, 'Withdrawal exceeds ₹1,00,000'),

    ('HIGH_VALUE_INSTANT_WITHDRAWAL', 'AMOUNT_THRESHOLD',
     '{"threshold": 25000, "transactionType": "WITHDRAWAL", "instantOnly": true}',
     0.50, 25, 'Instant withdrawal exceeds ₹25,000'),

    ('GEO_ANOMALY',                'GEO_ANOMALY',
     '{"highRiskCountryCodes": ["NG","RU","KP","IR","BY"]}',
     0.45, 40, 'Withdrawal to a high-risk jurisdiction'),

    ('OFF_HOURS',                  'OFF_HOURS',
     '{"startHour": 1, "endHour": 5, "timezone": "Asia/Kolkata"}',
     0.20, 50, 'Transaction initiated between 01:00–05:00 IST'),

    ('SELF_TRANSFER_ATTEMPT',      'CUSTOM',
     '{}',
     0.70, 5, 'Source and destination account belong to same owner'),

    ('HIGH_RISK_MCC_PAYMENT',      'CUSTOM',
     '{"mccCodes": [7995, 5912, 6012, 4829, 6211]}',
     0.40, 35, 'Merchant payment to high-risk MCC category');

-- ─────────────────────────────────────────────────────────────────────────────
-- FRAUD LOGS — one row per evaluated transaction
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE fraud_logs (
    id                  UUID            DEFAULT uuid_generate_v4() PRIMARY KEY,
    transaction_id      UUID            NOT NULL,           -- FK to transactions in txn-service DB
    source_account_id   VARCHAR(100)    NOT NULL,
    risk_score          NUMERIC(5, 4)   NOT NULL DEFAULT 0.0
                                        CHECK (risk_score BETWEEN 0 AND 1),
    verdict             VARCHAR(10)     NOT NULL
                                        CHECK (verdict IN ('PASS', 'FLAG', 'BLOCK')),
    triggered_rules     JSONB           NOT NULL DEFAULT '[]', -- array of rule names
    analysis_summary    TEXT,
    analyst_override    VARCHAR(20),                        -- APPROVE / ESCALATE (manual review)
    analyst_notes       TEXT,
    evaluated_at        TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    overridden_at       TIMESTAMPTZ
);

-- Fast lookup by transaction (joins from the verdict consumer)
CREATE INDEX idx_fraud_logs_txn
    ON fraud_logs (transaction_id);

-- Analyst dashboard: all flagged/blocked cases by recency
CREATE INDEX idx_fraud_logs_verdict_time
    ON fraud_logs (verdict, evaluated_at DESC)
    WHERE verdict IN ('FLAG', 'BLOCK');

-- Partial index for unreviewed flagged records (analyst queue)
CREATE INDEX idx_fraud_logs_unreviewed
    ON fraud_logs (evaluated_at ASC)
    WHERE verdict = 'FLAG' AND analyst_override IS NULL;

-- GIN index: query by triggered rule names
-- e.g. WHERE triggered_rules @> '["HIGH_VALUE_P2P"]'
CREATE INDEX idx_fraud_logs_rules_gin
    ON fraud_logs USING GIN (triggered_rules);

-- Account-level fraud history (risk profiling)
CREATE INDEX idx_fraud_logs_account
    ON fraud_logs (source_account_id, evaluated_at DESC);

COMMENT ON TABLE fraud_logs IS
    'Immutable audit log of every fraud evaluation. One row per transaction. Never updated except for analyst_override fields.';
COMMENT ON COLUMN fraud_logs.triggered_rules IS
    'JSONB array of rule_name strings that fired, e.g. ["HIGH_VALUE_P2P","OFF_HOURS"]';

-- ─────────────────────────────────────────────────────────────────────────────
-- VELOCITY COUNTERS — materialised rolling window for fast rule evaluation
-- ─────────────────────────────────────────────────────────────────────────────
-- Optional optimisation: instead of scanning fraud_logs for velocity,
-- maintain a pre-aggregated counter table updated on each evaluation.

CREATE TABLE velocity_counters (
    account_id          VARCHAR(100)    NOT NULL,
    window_type         VARCHAR(10)     NOT NULL CHECK (window_type IN ('1MIN', '1HOUR', '1DAY')),
    window_start        TIMESTAMPTZ     NOT NULL,
    transaction_count   INT             NOT NULL DEFAULT 0,
    total_amount        NUMERIC(19, 4)  NOT NULL DEFAULT 0,
    PRIMARY KEY (account_id, window_type, window_start)
);

CREATE INDEX idx_velocity_account_window
    ON velocity_counters (account_id, window_type, window_start DESC);

-- Auto-cleanup: index for locating stale rows older than 2 days
CREATE INDEX idx_velocity_cleanup
    ON velocity_counters (window_start);
