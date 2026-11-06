-- ============================================================
-- HomeEase Supabase SQL Editor — Manual Test Queries
-- PURPOSE: Standalone queries you can paste directly into the
--          Supabase SQL Editor without named parameters.
-- ============================================================
-- Named params like :eventId / :lat / :lon are Spring Boot
-- placeholders. The SQL Editor does NOT support them.
-- Replace the placeholder UUIDs/values below with real data.
-- ============================================================


-- ============================================================
-- TEST 1: booking_analytics_inbox (processed_events check)
-- ============================================================

-- Check if an event has already been processed:
SELECT COUNT(*)
FROM booking_analytics_inbox
WHERE event_id = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';  -- replace with real UUID

-- Mark an event as processed (idempotent):
INSERT INTO booking_analytics_inbox (event_id)
VALUES ('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee')           -- replace with real UUID
ON CONFLICT (event_id) DO NOTHING;

-- View recently processed events:
SELECT event_id, processed_at
FROM booking_analytics_inbox
ORDER BY processed_at DESC
LIMIT 20;


-- ============================================================
-- TEST 2: Redis/PostGIS spatial worker matching
-- If you see "column location is of type point but expression is of type geography",
-- run this block once to convert location to PostGIS GEOGRAPHY(POINT, 4326):
-- ============================================================
-- DO $$
-- BEGIN
--     IF EXISTS (
--         SELECT 1 FROM information_schema.columns 
--         WHERE table_name = 'workers' AND column_name = 'location' AND udt_name != 'geography'
--     ) THEN
--         ALTER TABLE workers DROP COLUMN location CASCADE;
--         ALTER TABLE workers ADD COLUMN location GEOGRAPHY(POINT, 4326);
--     END IF;
-- END $$;
--
-- Replace 12.9716 / 77.5946 with real customer coordinates.
-- Note: ST_MakePoint(longitude, latitude) — lon comes FIRST.
-- ============================================================

-- Workers within 5 km of a customer location:
SELECT
    w.worker_id,
    u.full_name,
    u.phone_number,
    w.current_lat,
    w.current_lng,
    ST_Distance(
        w.location,
        ST_SetSRID(ST_MakePoint(77.5946, 12.9716), 4326)::GEOGRAPHY
    ) AS distance_meters
FROM workers w
JOIN users u ON u.user_id = w.user_id
WHERE w.is_online   = true
  AND w.is_verified = true
  AND w.location IS NOT NULL
  AND ST_DWithin(
        w.location,
        ST_SetSRID(ST_MakePoint(77.5946, 12.9716), 4326)::GEOGRAPHY,
        5000  -- 5 000 m = 5 km radius
      )
ORDER BY distance_meters ASC
LIMIT 20;

-- Check PostGIS is enabled:
SELECT extname, extversion FROM pg_extension WHERE extname = 'postgis';

-- Check which workers have a location geometry populated:
SELECT worker_id, current_lat, current_lng,
       ST_AsText(location) AS location_wkt
FROM workers
WHERE location IS NOT NULL
LIMIT 10;

-- Manually backfill location for a single worker (if trigger hasn't run yet):
UPDATE workers
SET location = ST_SetSRID(ST_MakePoint(current_lng, current_lat), 4326)::GEOGRAPHY
WHERE worker_id = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'  -- replace with real worker UUID
  AND current_lat IS NOT NULL
  AND current_lng IS NOT NULL;


-- ============================================================
-- TEST 3: Bookings table indexes — correct column is user_id
-- ============================================================

-- Verify bookings table columns (should NOT contain customer_id):
SELECT column_name, data_type
FROM information_schema.columns
WHERE table_name = 'bookings'
ORDER BY ordinal_position;

-- Customer booking history (uses idx_bookings_user_status_created):
SELECT booking_id, status, total_amount, created_at
FROM bookings
WHERE user_id = '00000000-0000-0000-0000-000000000001'  -- replace with real user UUID
ORDER BY created_at DESC
LIMIT 10;

-- Active bookings for a customer:
SELECT booking_id, status, scheduled_at
FROM bookings
WHERE user_id = '00000000-0000-0000-0000-000000000001'  -- replace with real user UUID
  AND status IN ('ACCEPTED', 'ON_THE_WAY', 'ARRIVED', 'IN_PROGRESS');

-- Check indexes exist on bookings:
SELECT indexname, indexdef
FROM pg_indexes
WHERE tablename = 'bookings'
ORDER BY indexname;


-- ============================================================
-- TEST 4: Partitioned bookings table structure check
-- ============================================================

-- Confirm partition table exists:
SELECT tablename
FROM pg_tables
WHERE tablename LIKE 'bookings_partitioned%'
ORDER BY tablename;

-- Check partition definitions:
SELECT
    nmsp_parent.nspname AS parent_schema,
    parent.relname      AS parent_table,
    nmsp_child.nspname  AS child_schema,
    child.relname       AS child_table
FROM pg_inherits
JOIN pg_class       parent      ON pg_inherits.inhparent = parent.oid
JOIN pg_class       child       ON pg_inherits.inhrelid  = child.oid
JOIN pg_namespace   nmsp_parent ON nmsp_parent.oid       = parent.relnamespace
JOIN pg_namespace   nmsp_child  ON nmsp_child.oid        = child.relnamespace
WHERE parent.relname = 'bookings_partitioned';

-- Count rows available to migrate:
SELECT COUNT(*) FROM bookings;

-- Preview data migration (run INSERT commented in V17 when ready):
SELECT
    booking_id, user_id, worker_id, service_id, status,
    booking_type, created_at
FROM bookings
ORDER BY created_at
LIMIT 5;

