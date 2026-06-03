-- V1__init_transaction_schema.sql
-- Transaction Service — PostgreSQL schema
-- Managed by Flyway. Hibernate DDL is set to 'validate' — never 'create' or 'update' in production.

-- ─────────────────────────────────────────────────────────────────────────────
-- EXTENSIONS
-- ─────────────────────────────────────────────────────────────────────────────

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";   -- UUID generation functions
CREATE EXTENSION IF NOT EXISTS "pg_trgm";     -- trigram indexes for text search
CREATE EXTENSION IF NOT EXISTS "btree_gin";   -- GIN support for scalar types

-- ─────────────────────────────────────────────────────────────────────────────
-- ACCOUNTS
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE accounts (
    id                  UUID            DEFAULT uuid_generate_v4() PRIMARY KEY,
    owner_id            VARCHAR(100)    NOT NULL,
    account_number      VARCHAR(20)     NOT NULL,
    account_type        VARCHAR(20)     NOT NULL CHECK (account_type IN ('PERSONAL', 'MERCHANT', 'ESCROW')),
    available_balance   NUMERIC(19, 4)  NOT NULL DEFAULT 0.0000,
    reserved_balance    NUMERIC(19, 4)  NOT NULL DEFAULT 0.0000,
    currency            CHAR(3)         NOT NULL DEFAULT 'INR',
    status              VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE'
                                        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    merchant_id         VARCHAR(100),                        -- NULL for personal accounts
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT accounts_balance_nonnegative
        CHECK (available_balance >= 0 AND reserved_balance >= 0),
    CONSTRAINT accounts_number_unique UNIQUE (account_number)
);

-- Hash index: O(1) lookup by owner_id (equality-only, never range scanned)
CREATE INDEX idx_accounts_owner_hash   ON accounts USING HASH (owner_id);

-- B-tree: sorted lookup + unique enforcement on account_number
CREATE UNIQUE INDEX idx_accounts_number ON accounts (account_number);

-- Partial index: only active accounts need fast lookup (most queries filter status = 'ACTIVE')
CREATE INDEX idx_accounts_active       ON accounts (owner_id) WHERE status = 'ACTIVE';

-- Merchant account lookup
CREATE INDEX idx_accounts_merchant     ON accounts (merchant_id) WHERE merchant_id IS NOT NULL;

COMMENT ON TABLE accounts IS
    'Wallet accounts. available_balance + reserved_balance = total funds held.';
COMMENT ON COLUMN accounts.reserved_balance IS
    'Funds escrowed for in-flight transactions awaiting fraud clearance.';

-- ─────────────────────────────────────────────────────────────────────────────
-- TRANSACTIONS — range-partitioned by created_at (monthly)
-- ─────────────────────────────────────────────────────────────────────────────
-- Partitioning rationale:
--   Transactions are append-only, time-series data. Monthly partitions mean:
--     1. Old partitions can be archived/detached without DELETE-based purges.
--     2. Queries with a created_at range filter only scan relevant partitions.
--     3. VACUUM runs per-partition, not on the whole table.

CREATE TABLE transactions (
    id                      UUID            NOT NULL,
    idempotency_key         VARCHAR(128)    NOT NULL,
    source_account_id       UUID            NOT NULL REFERENCES accounts(id),
    amount                  NUMERIC(19, 4)  NOT NULL CHECK (amount > 0),
    currency                CHAR(3)         NOT NULL,
    type_kind               VARCHAR(30)     NOT NULL
                                            CHECK (type_kind IN (
                                                'PEER_TO_PEER', 'WITHDRAWAL',
                                                'DEPOSIT', 'MERCHANT_PAYMENT')),
    type_payload            JSONB           NOT NULL DEFAULT '{}',
    status                  VARCHAR(30)     NOT NULL DEFAULT 'INITIATED'
                                            CHECK (status IN (
                                                'INITIATED', 'PENDING_FRAUD_CHECK',
                                                'FRAUD_FLAGGED', 'FRAUD_BLOCKED',
                                                'PROCESSING', 'PROCESSED', 'FAILED', 'REVERSED')),
    initiated_by_user_id    VARCHAR(100)    NOT NULL,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    processed_at            TIMESTAMPTZ,

    PRIMARY KEY (id, created_at)            -- partition key must be part of PK
) PARTITION BY RANGE (created_at);

-- ── Monthly partitions (auto-created; shown here for reference) ──────────────
CREATE TABLE transactions_2025_01
    PARTITION OF transactions
    FOR VALUES FROM ('2025-01-01') TO ('2025-02-01');

CREATE TABLE transactions_2025_02
    PARTITION OF transactions
    FOR VALUES FROM ('2025-02-01') TO ('2025-03-01');

-- In production: a pg_cron job or Flyway migration auto-creates future partitions.

-- ── Indexes on the parent table (inherited by all partitions) ─────────────────

-- Primary fraud-check lookup: "pending transactions for an account, newest first"
CREATE INDEX idx_txn_source_created
    ON transactions (source_account_id, created_at DESC);

-- Idempotency enforcement (unique across all partitions)
CREATE UNIQUE INDEX idx_txn_idempotency
    ON transactions (idempotency_key);

-- Status filter: fraud queue workers poll for PENDING_FRAUD_CHECK rows
-- Partial index — only indexes the subset of rows actually needing work
CREATE INDEX idx_txn_pending_fraud
    ON transactions (created_at)
    WHERE status = 'PENDING_FRAUD_CHECK';

-- BRIN index: time-range queries on large partitions (very low storage overhead)
CREATE INDEX idx_txn_created_brin
    ON transactions USING BRIN (created_at) WITH (pages_per_range = 32);

-- GIN index on type_payload JSONB: enables efficient @> (contains) queries
-- e.g. SELECT * FROM transactions WHERE type_payload @> '{"merchantId":"abc"}'
CREATE INDEX idx_txn_type_payload_gin
    ON transactions USING GIN (type_payload);

COMMENT ON TABLE transactions IS
    'Append-only ledger. Partitioned by created_at (monthly). Never UPDATE amount or source.';

-- ─────────────────────────────────────────────────────────────────────────────
-- OUTBOX EVENTS — transactional outbox for reliable Kafka publishing
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE outbox_events (
    id              UUID            DEFAULT uuid_generate_v4() PRIMARY KEY,
    aggregate_type  VARCHAR(50)     NOT NULL,          -- e.g. 'Transaction'
    aggregate_id    UUID            NOT NULL,
    event_type      VARCHAR(80)     NOT NULL,          -- e.g. 'TransactionCreated'
    topic           VARCHAR(100)    NOT NULL,
    payload         JSONB           NOT NULL,
    processed       BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    processed_at    TIMESTAMPTZ
);

-- The outbox poller queries: WHERE processed = FALSE ORDER BY created_at ASC
-- Partial index covers only unprocessed rows — tiny, fast
CREATE INDEX idx_outbox_unprocessed
    ON outbox_events (created_at ASC)
    WHERE processed = FALSE;

COMMENT ON TABLE outbox_events IS
    'Transactional outbox. Written in same TX as business data. Polled by OutboxPoller to publish to Kafka.';

-- ─────────────────────────────────────────────────────────────────────────────
-- UPDATED_AT auto-trigger for accounts
-- ─────────────────────────────────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_accounts_updated_at
    BEFORE UPDATE ON accounts
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
