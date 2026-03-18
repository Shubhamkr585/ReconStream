CREATE EXTENSION IF NOT EXISTS vector;

-- 1. Accounts (No mutable balance column!)
CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    code VARCHAR(64) UNIQUE NOT NULL,        -- e.g., 'MERCHANT_PAYABLE', 'PSP_CLEARING', 'MDR_FEE_EXPENSE'
    account_type VARCHAR(20) NOT NULL CHECK (account_type IN ('ASSET', 'LIABILITY', 'REVENUE', 'EXPENSE')),
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 2. Append-Only Double-Entry Ledger
CREATE TABLE journal_entries (
    id UUID PRIMARY KEY,
    reference_id VARCHAR(128) NOT NULL,      -- payment_order_id or settlement_id
    event_type VARCHAR(64) NOT NULL,         -- 'CAPTURE', 'REFUND', 'SETTLEMENT_FEE'
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (reference_id, event_type)        -- DB-level guard against duplicate postings
);

CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    journal_entry_id UUID NOT NULL REFERENCES journal_entries(id),
    account_id UUID NOT NULL REFERENCES accounts(id),
    amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
    direction VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 3. Strict Idempotency Store
CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,       -- SHA-256 of canonical request payload
    status VARCHAR(20) NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')),
    http_status INT,
    response_body JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 4. Payment Orders & State Machine
CREATE TABLE payment_orders (
    id UUID PRIMARY KEY,
    external_order_id VARCHAR(128) UNIQUE NOT NULL,
    psp_txn_id VARCHAR(128) UNIQUE,
    amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
    expected_mdr_bps INT NOT NULL DEFAULT 200, -- 2.00% = 200 basis points
    state VARCHAR(32) NOT NULL CHECK (state IN ('INITIATED', 'PENDING', 'AUTHORIZED', 'CAPTURED', 'SETTLED', 'FAILED', 'REFUNDED')),
    version BIGINT NOT NULL DEFAULT 0,         -- Optimistic locking (@Version)
    last_polled_at TIMESTAMPTZ,
    poll_attempts INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 5. Transactional Outbox (Zero Lost Kafka Events)
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_pending ON outbox_events (created_at) WHERE status = 'PENDING';

-- 6. Three-Way Reconciliation Discrepancies + pgvector
CREATE TABLE recon_discrepancies (
    id UUID PRIMARY KEY,
    payment_order_id UUID REFERENCES payment_orders(id),
    psp_txn_id VARCHAR(128),
    discrepancy_type VARCHAR(32) NOT NULL CHECK (
        discrepancy_type IN ('FEE_LEAKAGE', 'GHOST_SETTLEMENT', 'STUCK_PENDING', 'AMOUNT_MISMATCH', 'MISSING_SETTLEMENT')
    ),
    expected_paise BIGINT NOT NULL,
    actual_paise BIGINT NOT NULL,
    diff_paise BIGINT NOT NULL,
    description TEXT NOT NULL,
    ai_summary TEXT,
    embedding vector(768),                    -- Gemini text-embedding-004 dimension
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'INVESTIGATING', 'RESOLVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
