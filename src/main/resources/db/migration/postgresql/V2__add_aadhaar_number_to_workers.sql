-- Add aadhaar_number to workers table
ALTER TABLE workers ADD COLUMN IF NOT EXISTS aadhaar_number VARCHAR(20);
