-- ====================================================================
-- V26: Repair the legacy bookings.user_location trigger on databases that do not have that column
-- ====================================================================
-- V3 installed trg_booking_user_location, whose function assigns NEW.user_location. That column exists only in
-- databases created from the legacy root schema (db/migration/V1__initial_schema.sql, which is NOT part of this
-- migration location). A database built from THIS chain (postgresql/V1 .. V25) never gets the column, so on such a
-- database every INSERT or UPDATE on bookings failed with:
--     ERROR: record "new" has no field "user_location"
--
-- The JPA entity does not persist this column (Booking.userLocation is @Transient), so the trigger has nothing to do
-- on a database without it. This migration removes the trigger and its function ONLY when the column is missing,
-- i.e. only where the trigger is provably broken. Where the column exists it changes nothing. Safe to re-run.
-- (V3 is left untouched on purpose: editing an applied migration would break Flyway's checksum validation.)

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_attribute
        WHERE attrelid = 'bookings'::regclass
          AND attname = 'user_location'
          AND NOT attisdropped
    ) THEN
        DROP TRIGGER IF EXISTS trg_booking_user_location ON bookings;
        DROP FUNCTION IF EXISTS set_booking_user_location();
    END IF;
END $$;
