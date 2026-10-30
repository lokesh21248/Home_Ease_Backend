-- ====================================================================
-- HomeEase PostgreSQL Migration V10: Simplify Serviceable Pincodes
-- 1. Make city_id and state_id optional (nullable) metadata for future expansion
-- 2. Drop single-pincode unique constraint to allow multiple localities per PIN code
-- 3. Add composite unique index on (pincode, area_name) for duplicate prevention
-- ====================================================================

-- 1. Make city_id and state_id nullable
ALTER TABLE serviceable_pincodes ALTER COLUMN city_id DROP NOT NULL;
ALTER TABLE serviceable_pincodes ALTER COLUMN state_id DROP NOT NULL;

-- 2. Drop single-pincode unique constraint
ALTER TABLE serviceable_pincodes DROP CONSTRAINT IF EXISTS uq_pincodes_pincode;

-- 3. Ensure fast lookup by pincode still has an index
CREATE INDEX IF NOT EXISTS idx_pincodes_pincode ON serviceable_pincodes(pincode);

-- 4. Case-insensitive composite unique index on (pincode, locality) to prevent duplicate localities under the same PIN code
CREATE UNIQUE INDEX IF NOT EXISTS uq_pincodes_pincode_area_norm
    ON serviceable_pincodes (pincode, COALESCE(UPPER(TRIM(area_name)), ''));
