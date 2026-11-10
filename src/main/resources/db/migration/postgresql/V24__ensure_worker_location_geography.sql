-- ====================================================================
-- V24: Ensure PostGIS geography column and GiST spatial index on workers
-- Replaces full-table spherical scans with fast index-accelerated ST_DWithin
-- ====================================================================

-- 1. Ensure PostGIS extension is active
CREATE EXTENSION IF NOT EXISTS postgis;

-- 2. Add location column if not already present
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 
        FROM information_schema.columns 
        WHERE table_name = 'workers' 
          AND column_name = 'location'
    ) THEN
        ALTER TABLE workers ADD COLUMN location GEOGRAPHY(POINT, 4326);
    END IF;
END $$;

-- 3. Backfill any worker coordinates that are missing geography point
UPDATE workers
SET location = ST_SetSRID(ST_MakePoint(current_lng, current_lat), 4326)::GEOGRAPHY
WHERE current_lat IS NOT NULL
  AND current_lng IS NOT NULL
  AND (location IS NULL OR ST_AsText(location) IS NULL);

-- 4. Ensure GIST spatial index exists on workers (location)
CREATE INDEX IF NOT EXISTS idx_workers_location_gist
    ON workers USING GIST (location);

-- 5. Partial GiST index for online + verified workers
CREATE INDEX IF NOT EXISTS idx_workers_location_online_verified
    ON workers USING GIST (location)
    WHERE is_online = true
      AND is_verified = true
      AND location IS NOT NULL;

