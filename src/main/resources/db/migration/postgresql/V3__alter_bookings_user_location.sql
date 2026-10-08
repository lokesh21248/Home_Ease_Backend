-- Fix: Make user_location column nullable in bookings so insertions with user_lat and user_lng succeed
DO $$ 
BEGIN 
    IF EXISTS (
        SELECT 1 
        FROM information_schema.columns 
        WHERE table_name = 'bookings' AND column_name = 'user_location'
    ) THEN 
        ALTER TABLE bookings ALTER COLUMN user_location DROP NOT NULL;
    END IF; 
END $$;

-- Optional trigger: Automatically populate user_location from user_lng and user_lat if provided
CREATE OR REPLACE FUNCTION set_booking_user_location() 
RETURNS trigger AS $$
BEGIN
    IF NEW.user_location IS NULL AND NEW.user_lat IS NOT NULL AND NEW.user_lng IS NOT NULL THEN
        NEW.user_location := point(NEW.user_lng, NEW.user_lat);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_booking_user_location ON bookings;
CREATE TRIGGER trg_booking_user_location 
BEFORE INSERT OR UPDATE ON bookings
FOR EACH ROW 
EXECUTE FUNCTION set_booking_user_location();
