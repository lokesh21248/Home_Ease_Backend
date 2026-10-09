-- ==============================================
-- Migration V4: Worker Profile & Multi-step KYC
-- ==============================================

-- 1. Allow temporary uncompleted profiles during registration
ALTER TABLE users ALTER COLUMN full_name DROP NOT NULL;

-- 2. Add avatar, gender, dob, experience and kyc_status to workers table
ALTER TABLE workers ADD COLUMN IF NOT EXISTS avatar_url TEXT;
ALTER TABLE workers ADD COLUMN IF NOT EXISTS gender VARCHAR(10);
ALTER TABLE workers ADD COLUMN IF NOT EXISTS dob DATE;
ALTER TABLE workers ADD COLUMN IF NOT EXISTS experience_years INTEGER DEFAULT 1;
ALTER TABLE workers ADD COLUMN IF NOT EXISTS kyc_status VARCHAR(20) NOT NULL DEFAULT 'NOT_UPLOADED';

-- 3. Make KYC document columns nullable until registration step 5 is complete
ALTER TABLE workers ALTER COLUMN address DROP NOT NULL;
ALTER TABLE workers ALTER COLUMN pan_number DROP NOT NULL;
ALTER TABLE workers ALTER COLUMN pan_doc_url DROP NOT NULL;
ALTER TABLE workers ALTER COLUMN aadhaar_doc_url DROP NOT NULL;
ALTER TABLE workers ALTER COLUMN bank_account_no DROP NOT NULL;
ALTER TABLE workers ALTER COLUMN bank_ifsc DROP NOT NULL;

-- 4. Index on kyc_status
CREATE INDEX IF NOT EXISTS idx_workers_kyc_status ON workers(kyc_status);
