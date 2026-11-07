-- ====================================================================
-- HomeEase PostgreSQL Migration V22: Make worker_id nullable in booking_slots
-- Allows capacity slot window reservations and holds prior to worker dispatch assignment
-- ====================================================================

ALTER TABLE booking_slots ALTER COLUMN worker_id DROP NOT NULL;
