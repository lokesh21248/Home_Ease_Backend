-- V6: Add optional idempotency key to bookings table
-- Column is nullable so all existing rows are unaffected.
-- Unique index prevents duplicate inserts with the same client-supplied key.

ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS idx_bookings_idempotency_key
    ON bookings(idempotency_key)
    WHERE idempotency_key IS NOT NULL;
