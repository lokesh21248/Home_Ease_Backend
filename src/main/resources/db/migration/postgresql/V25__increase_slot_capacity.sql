-- ====================================================================
-- HomeEase PostgreSQL Migration V25: Increase Default Slot Capacity
-- Increases default slot window capacity from 3 to 10 so that
-- customers tapping slots multiple times in a session do not exhaust
-- the window and get 409 CONFLICT errors.
-- Also expires any RESERVED holds older than 2 minutes at migration time.
-- ====================================================================

-- 1. Update existing seeded slot_window_capacity rows to max_capacity=10
UPDATE slot_window_capacity
SET max_capacity = 10,
    updated_at   = NOW()
WHERE max_capacity <= 3;

-- 2. Expire any RESERVED holds older than 2 minutes (cleanup leftover test holds)
UPDATE booking_slots
SET status = 'EXPIRED'
WHERE status = 'RESERVED'
  AND created_at < NOW() - INTERVAL '2 minutes';
