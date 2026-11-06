-- ====================================================================
-- HomeEase PostgreSQL Migration V17: Performance Fixes & Analytics
-- Fixes all 4 SQL errors identified in Supabase SQL Editor:
--   1. processed_events query  → use booking_analytics_inbox (correct table)
--   2. PostGIS worker matching → ST_SetSRID + ST_MakePoint with literals
--   3. bookings indexes        → bookings uses user_id (not customer_id)
--   4. Table partitioning      → no-downtime range partitioning via new table
-- ====================================================================


-- ====================================================================
-- FIX 1: booking_analytics_inbox processed-event check
-- The table is booking_analytics_inbox, PK is event_id (UUID).
-- :eventId is a Spring named parameter — in SQL Editor use a literal UUID.
-- Application queries use this table to skip already-processed events.
-- ====================================================================

-- Idempotency check query (application-side — do NOT run in SQL Editor):
--   SELECT COUNT(*) FROM booking_analytics_inbox WHERE event_id = :eventId
--
-- SQL Editor equivalent (replace with real UUID to test):
--   SELECT COUNT(*) FROM booking_analytics_inbox
--   WHERE event_id = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';

-- Mark an event as processed (application-side):
--   INSERT INTO booking_analytics_inbox (event_id) VALUES (:eventId)
--   ON CONFLICT (event_id) DO NOTHING;
--
-- SQL Editor equivalent:
--   INSERT INTO booking_analytics_inbox (event_id)
--   VALUES ('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee')
--   ON CONFLICT (event_id) DO NOTHING;

-- Supporting index: event_id is already the primary key (automatically indexed).
-- An index on processed_at is beneficial for pruning / time-range queries:
CREATE INDEX IF NOT EXISTS idx_booking_analytics_inbox_processed_at
    ON booking_analytics_inbox (processed_at DESC);


-- ====================================================================
-- FIX 2: PostGIS spatial worker matching
-- Error was: ST_MakePoint(:lon, :lat) — named params not allowed in SQL Editor.
-- Fix: add explicit SRID 4326 via ST_SetSRID, use literal coords for testing.
-- Application code (Spring JDBC) may still use ? or :param placeholders.
-- ====================================================================

-- Ensure PostGIS extension is available (Supabase has it enabled by default):
CREATE EXTENSION IF NOT EXISTS postgis;

-- Add or convert workers.location to PostGIS geography(Point, 4326).
-- Handles legacy databases where 'location' was created as PostgreSQL native 'point':
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 
        FROM information_schema.columns 
        WHERE table_name = 'workers' 
          AND column_name = 'location' 
          AND udt_name != 'geography'
    ) THEN
        ALTER TABLE workers DROP COLUMN location CASCADE;
        ALTER TABLE workers ADD COLUMN location GEOGRAPHY(POINT, 4326);
    ELSIF NOT EXISTS (
        SELECT 1 
        FROM information_schema.columns 
        WHERE table_name = 'workers' 
          AND column_name = 'location'
    ) THEN
        ALTER TABLE workers ADD COLUMN location GEOGRAPHY(POINT, 4326);
    END IF;
END $$;

-- Backfill existing workers that already have lat/lng:
UPDATE workers
SET location = ST_SetSRID(ST_MakePoint(current_lng, current_lat), 4326)::GEOGRAPHY
WHERE current_lat IS NOT NULL
  AND current_lng IS NOT NULL;

-- GIST spatial index for sub-5km radius dispatch queries:
CREATE INDEX IF NOT EXISTS idx_workers_location_gist
    ON workers USING GIST (location);

-- Partial index: only index online+verified workers with a known location
-- (matches the WHERE clause used in dispatch, minimising index size):
CREATE INDEX IF NOT EXISTS idx_workers_location_online_verified
    ON workers USING GIST (location)
    WHERE is_online = true
      AND is_verified = true
      AND location IS NOT NULL;

-- ----------------------------------------------------------------
-- SQL Editor test query for spatial worker matching (5 km radius):
-- Replace 12.9716 / 77.5946 with the real customer coordinates.
-- ----------------------------------------------------------------
-- SELECT
--     w.worker_id,
--     w.current_lat,
--     w.current_lng,
--     ST_Distance(
--         w.location,
--         ST_SetSRID(ST_MakePoint(77.5946, 12.9716), 4326)::GEOGRAPHY
--     ) AS distance_meters
-- FROM workers w
-- WHERE w.is_online    = true
--   AND w.is_verified  = true
--   AND w.location IS NOT NULL
--   AND ST_DWithin(
--         w.location,
--         ST_SetSRID(ST_MakePoint(77.5946, 12.9716), 4326)::GEOGRAPHY,
--         5000   -- 5 000 metres = 5 km
--       )
-- ORDER BY distance_meters ASC
-- LIMIT 20;

-- ----------------------------------------------------------------
-- Trigger to keep the geography column in sync when lat/lng changes:
-- ----------------------------------------------------------------
CREATE OR REPLACE FUNCTION sync_worker_location()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.current_lat IS NOT NULL AND NEW.current_lng IS NOT NULL THEN
        NEW.location := ST_SetSRID(
            ST_MakePoint(NEW.current_lng, NEW.current_lat),
            4326
        )::GEOGRAPHY;
    ELSE
        NEW.location := NULL;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_sync_worker_location ON workers;
CREATE TRIGGER trg_sync_worker_location
    BEFORE INSERT OR UPDATE OF current_lat, current_lng
    ON workers
    FOR EACH ROW
    EXECUTE FUNCTION sync_worker_location();


-- ====================================================================
-- FIX 3: Bookings indexes — bookings uses user_id, NOT customer_id
-- The column was confirmed as user_id in V1__initial_schema_postgresql.sql.
-- All indexes below reference the correct column names.
-- ====================================================================

-- Composite covering index: customer booking history (most common query):
CREATE INDEX IF NOT EXISTS idx_bookings_user_status_created
    ON bookings (user_id, status, created_at DESC);

-- Composite index: admin dashboard — bookings by status + created date:
CREATE INDEX IF NOT EXISTS idx_bookings_status_created
    ON bookings (status, created_at DESC);

-- Composite index: worker active job + scheduled range:
CREATE INDEX IF NOT EXISTS idx_bookings_worker_status_created
    ON bookings (worker_id, status, created_at DESC)
    WHERE worker_id IS NOT NULL;

-- Partial index: bookings awaiting dispatch (SEARCHING state):
CREATE INDEX IF NOT EXISTS idx_bookings_searching
    ON bookings (created_at DESC)
    WHERE status = 'SEARCHING';

-- Partial index: active / in-flight bookings for dashboard:
CREATE INDEX IF NOT EXISTS idx_bookings_active
    ON bookings (user_id, scheduled_at)
    WHERE status IN ('ACCEPTED', 'ON_THE_WAY', 'ARRIVED', 'IN_PROGRESS');

-- Partial index: revenue reporting (completed + paid):
CREATE INDEX IF NOT EXISTS idx_bookings_completed_paid
    ON bookings (created_at DESC)
    WHERE status = 'COMPLETED' AND payment_status = 'SUCCESS';

-- ----------------------------------------------------------------
-- SQL Editor test query (uses correct column user_id):
-- ----------------------------------------------------------------
-- SELECT booking_id, user_id, status, created_at
-- FROM bookings
-- WHERE user_id = '00000000-0000-0000-0000-000000000001'
-- ORDER BY created_at DESC
-- LIMIT 10;


-- ====================================================================
-- FIX 4: Table partitioning — safe, zero-downtime approach
-- We do NOT use CREATE TABLE bookings (...) with "..." placeholders.
-- Strategy: create a new partitioned shadow table, migrate data, swap.
-- This migration creates the infrastructure; the actual swap is a DBA op.
-- ====================================================================

-- Step 4a: Partitioned shadow table (range on created_at by month):
CREATE TABLE IF NOT EXISTS bookings_partitioned (
    booking_id              UUID         NOT NULL,
    user_id                 UUID         NOT NULL,
    worker_id               UUID,
    service_id              UUID         NOT NULL,
    coupon_id               UUID,
    status                  VARCHAR(30)  NOT NULL DEFAULT 'SEARCHING',
    booking_type            VARCHAR(20)  NOT NULL DEFAULT 'INSTANT',
    pin_code                CHAR(4)      NOT NULL,
    scheduled_at            TIMESTAMP    NOT NULL,
    scheduled_end_at        TIMESTAMP,
    started_at              TIMESTAMP,
    completed_at            TIMESTAMP,
    cancelled_at            TIMESTAMP,
    cancelled_by            UUID,
    cancellation_reason     TEXT,
    worker_assigned_at      TIMESTAMP,
    estimated_arrival_minutes INT,
    slot_id                 UUID,
    user_lat                DOUBLE PRECISION NOT NULL,
    user_lng                DOUBLE PRECISION NOT NULL,
    base_amount             DECIMAL(10,2) NOT NULL,
    extra_amount            DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    discount_amount         DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    total_amount            DECIMAL(10,2) NOT NULL,
    payment_status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    payment_method          VARCHAR(20)  NOT NULL,
    created_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (booking_id, created_at)
) PARTITION BY RANGE (created_at);

-- Step 4b: Monthly partitions (create as needed; extend with a cron job):
CREATE TABLE IF NOT EXISTS bookings_partitioned_2026_01
    PARTITION OF bookings_partitioned
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');

CREATE TABLE IF NOT EXISTS bookings_partitioned_2026_02
    PARTITION OF bookings_partitioned
    FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');

CREATE TABLE IF NOT EXISTS bookings_partitioned_2026_03
    PARTITION OF bookings_partitioned
    FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');

CREATE TABLE IF NOT EXISTS bookings_partitioned_2026_04
    PARTITION OF bookings_partitioned
    FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');

CREATE TABLE IF NOT EXISTS bookings_partitioned_2026_05
    PARTITION OF bookings_partitioned
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');

CREATE TABLE IF NOT EXISTS bookings_partitioned_2026_06
    PARTITION OF bookings_partitioned
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');

CREATE TABLE IF NOT EXISTS bookings_partitioned_2026_07
    PARTITION OF bookings_partitioned
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');

CREATE TABLE IF NOT EXISTS bookings_partitioned_2026_08
    PARTITION OF bookings_partitioned
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');

CREATE TABLE IF NOT EXISTS bookings_partitioned_2026_09
    PARTITION OF bookings_partitioned
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');

CREATE TABLE IF NOT EXISTS bookings_partitioned_2026_10
    PARTITION OF bookings_partitioned
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');

CREATE TABLE IF NOT EXISTS bookings_partitioned_2026_11
    PARTITION OF bookings_partitioned
    FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');

CREATE TABLE IF NOT EXISTS bookings_partitioned_2026_12
    PARTITION OF bookings_partitioned
    FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');

CREATE TABLE IF NOT EXISTS bookings_partitioned_2027_01
    PARTITION OF bookings_partitioned
    FOR VALUES FROM ('2027-01-01') TO ('2027-02-01');

-- Default partition catches anything outside the named ranges:
CREATE TABLE IF NOT EXISTS bookings_partitioned_default
    PARTITION OF bookings_partitioned DEFAULT;

-- Step 4c: Indexes on the partitioned table (inherited by all partitions):
CREATE INDEX IF NOT EXISTS idx_bp_user_created
    ON bookings_partitioned (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_bp_worker_status
    ON bookings_partitioned (worker_id, status)
    WHERE worker_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_bp_status_created
    ON bookings_partitioned (status, created_at DESC);

-- ----------------------------------------------------------------
-- Step 4d: Data migration helper view (run manually in SQL Editor):
-- INSERT INTO bookings_partitioned
-- SELECT
--     booking_id, user_id, worker_id, service_id, coupon_id,
--     status, booking_type, pin_code, scheduled_at, scheduled_end_at,
--     started_at, completed_at, cancelled_at, cancelled_by,
--     cancellation_reason, worker_assigned_at, estimated_arrival_minutes,
--     slot_id, user_lat, user_lng, base_amount, extra_amount,
--     discount_amount, total_amount, payment_status, payment_method, created_at
-- FROM bookings
-- ON CONFLICT DO NOTHING;
-- ----------------------------------------------------------------

-- ====================================================================
-- END OF V17
-- ====================================================================

