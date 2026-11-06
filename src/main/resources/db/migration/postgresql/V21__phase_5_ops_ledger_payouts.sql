-- ============================================================================
-- V21: Phase 5 Operations Console, Double-Entry Ledger, Disputes, and Payouts
-- ============================================================================

-- 1. Double-Entry Ledger Accounts
CREATE TABLE IF NOT EXISTS ledger_accounts (
    account_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_type VARCHAR(50) NOT NULL,
    entity_id UUID, -- worker_id for partner payable accounts; NULL for system accounts
    balance NUMERIC(14, 2) NOT NULL DEFAULT 0.00,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_ledger_account_type_entity
    ON ledger_accounts (account_type, COALESCE(entity_id, '00000000-0000-0000-0000-000000000000'));

-- Seed Fundamental System Ledger Accounts
INSERT INTO ledger_accounts (account_id, account_type, entity_id, balance, currency)
VALUES
    ('b0000000-0000-0000-0000-000000000001', 'ASSET_CASH', NULL, 1000000.00, 'INR'),
    ('b0000000-0000-0000-0000-000000000002', 'LIABILITY_CUSTOMER_ESCROW', NULL, 0.00, 'INR'),
    ('b0000000-0000-0000-0000-000000000003', 'REVENUE_PLATFORM_FEE', NULL, 0.00, 'INR'),
    ('b0000000-0000-0000-0000-000000000004', 'EXPENSE_DISPUTE_RESERVE', NULL, 50000.00, 'INR')
ON CONFLICT (account_type, COALESCE(entity_id, '00000000-0000-0000-0000-000000000000')) DO NOTHING;

-- 2. Double-Entry Journal Entries (Rule #2: Idempotency Key)
CREATE TABLE IF NOT EXISTS ledger_journal_entries (
    entry_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    booking_id UUID REFERENCES bookings(booking_id) ON DELETE SET NULL,
    transaction_type VARCHAR(50) NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_journal_booking
    ON ledger_journal_entries (booking_id);

CREATE INDEX IF NOT EXISTS idx_journal_type_date
    ON ledger_journal_entries (transaction_type, created_at);

-- 3. Double-Entry Postings (Debits and Credits)
CREATE TABLE IF NOT EXISTS ledger_postings (
    posting_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entry_id UUID NOT NULL REFERENCES ledger_journal_entries(entry_id) ON DELETE CASCADE,
    account_id UUID NOT NULL REFERENCES ledger_accounts(account_id),
    direction VARCHAR(10) NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount NUMERIC(14, 2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_postings_entry
    ON ledger_postings (entry_id);

CREATE INDEX IF NOT EXISTS idx_postings_account
    ON ledger_postings (account_id);

-- 4. Disputes Management Table
CREATE TABLE IF NOT EXISTS disputes (
    dispute_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id UUID NOT NULL REFERENCES bookings(booking_id) ON DELETE CASCADE,
    raised_by_user_id UUID REFERENCES users(user_id) ON DELETE SET NULL,
    reason VARCHAR(100) NOT NULL,
    description TEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'OPEN',
    resolution_notes TEXT,
    refund_amount NUMERIC(10, 2) DEFAULT 0.00,
    compensation_amount NUMERIC(10, 2) DEFAULT 0.00,
    resolved_by_admin_id UUID REFERENCES users(user_id) ON DELETE SET NULL,
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_disputes_booking
    ON disputes (booking_id);

CREATE INDEX IF NOT EXISTS idx_disputes_status
    ON disputes (status);

-- 5. Worker Payout Batches
CREATE TABLE IF NOT EXISTS worker_payout_batches (
    batch_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    worker_id UUID NOT NULL REFERENCES workers(worker_id) ON DELETE CASCADE,
    amount NUMERIC(10, 2) NOT NULL CHECK (amount > 0),
    status VARCHAR(50) NOT NULL DEFAULT 'INITIATED',
    razorpay_payout_id VARCHAR(100),
    failure_reason TEXT,
    compensating_entry_id UUID REFERENCES ledger_journal_entries(entry_id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    settled_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_payout_worker
    ON worker_payout_batches (worker_id, status);

-- 6. Add Zone Dispatch Wave Configuration to assignment_weight_config (Rule #10)
ALTER TABLE assignment_weight_config
    ADD COLUMN IF NOT EXISTS round1_radius_meters DOUBLE PRECISION NOT NULL DEFAULT 3000.0,
    ADD COLUMN IF NOT EXISTS round2_radius_meters DOUBLE PRECISION NOT NULL DEFAULT 5000.0,
    ADD COLUMN IF NOT EXISTS round3_radius_meters DOUBLE PRECISION NOT NULL DEFAULT 8000.0,
    ADD COLUMN IF NOT EXISTS surge_multiplier DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    ADD COLUMN IF NOT EXISTS max_speed_kmh_threshold DOUBLE PRECISION NOT NULL DEFAULT 150.0,
    ADD COLUMN IF NOT EXISTS is_zone_active BOOLEAN NOT NULL DEFAULT TRUE;

