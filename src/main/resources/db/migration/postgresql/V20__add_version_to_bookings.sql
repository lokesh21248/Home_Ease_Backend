-- V20: Non-negotiable Rule #8: Add optimistic version to bookings for guarded state writes
ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS version INTEGER NOT NULL DEFAULT 0;

