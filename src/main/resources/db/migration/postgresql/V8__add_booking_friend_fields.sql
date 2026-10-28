-- ====================================================================
-- HomeEase PostgreSQL Migration V8: Booking friend/recipient fields
-- + service-area pincode persistence
-- ====================================================================
-- All columns are nullable / have defaults so existing rows are
-- unaffected and backward compatibility is fully preserved.

ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS is_for_friend     BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS recipient_name    VARCHAR(100),
    ADD COLUMN IF NOT EXISTS recipient_phone   VARCHAR(20),
    ADD COLUMN IF NOT EXISTS recipient_address TEXT,
    ADD COLUMN IF NOT EXISTS service_pincode   VARCHAR(6);

COMMENT ON COLUMN bookings.is_for_friend
    IS 'TRUE when the booking was placed on behalf of another person (friend/family).';
COMMENT ON COLUMN bookings.recipient_name
    IS 'Full name of the service recipient; NULL for self-bookings.';
COMMENT ON COLUMN bookings.recipient_phone
    IS 'Mobile number of the service recipient; NULL for self-bookings.';
COMMENT ON COLUMN bookings.recipient_address
    IS 'Human-readable service address of the recipient; NULL for self-bookings.';
COMMENT ON COLUMN bookings.service_pincode
    IS '6-digit service-area pincode that was validated by assertServiceable() at booking creation time.';

-- Index for deactivation-impact queries (count bookings in a pincode)
CREATE INDEX IF NOT EXISTS idx_bookings_service_pincode
    ON bookings(service_pincode);
