-- ====================================================================
-- HomeEase PostgreSQL Migration V11: Dual Booking System & Worker Availability
-- Supports: Instant Booking, Scheduled Booking, Weekly Availability,
--           Break Times, Time Off / Blocked Dates, Dynamic Booking Slots,
--           and Overlapping Booking Protection.
-- ====================================================================

-- 1. Enable btree_gist for exclusion constraints if available
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- 2. Worker Weekly Availability Table
CREATE TABLE IF NOT EXISTS worker_availability (
    availability_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    worker_id UUID NOT NULL REFERENCES workers(worker_id) ON DELETE CASCADE,
    day_of_week INT NOT NULL CHECK (day_of_week BETWEEN 1 AND 7), -- 1 = Monday, 7 = Sunday
    is_available BOOLEAN NOT NULL DEFAULT true,
    start_time TIME NOT NULL DEFAULT '08:00:00',
    end_time TIME NOT NULL DEFAULT '20:00:00',
    break_start_time TIME NULL DEFAULT '13:00:00',
    break_end_time TIME NULL DEFAULT '14:00:00',
    slot_duration_mins INT NOT NULL DEFAULT 60,
    max_bookings_per_slot INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_worker_day_of_week UNIQUE (worker_id, day_of_week)
);

CREATE INDEX IF NOT EXISTS idx_worker_avail_lookup 
    ON worker_availability(worker_id, day_of_week, is_available);

-- 3. Worker Time-Off / Blocked Dates Table
CREATE TABLE IF NOT EXISTS worker_time_off (
    time_off_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    worker_id UUID NOT NULL REFERENCES workers(worker_id) ON DELETE CASCADE,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    reason VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_time_off_dates CHECK (end_date >= start_date)
);

CREATE INDEX IF NOT EXISTS idx_worker_time_off_dates 
    ON worker_time_off(worker_id, start_date, end_date);

-- 4. Booking Slots Table (Reserved & Confirmed Time Windows)
CREATE TABLE IF NOT EXISTS booking_slots (
    slot_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    worker_id UUID NOT NULL REFERENCES workers(worker_id) ON DELETE CASCADE,
    booking_id UUID REFERENCES bookings(booking_id) ON DELETE SET NULL,
    slot_date DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    start_timestamp TIMESTAMP NOT NULL,
    end_timestamp TIMESTAMP NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'RESERVED' CHECK (status IN ('AVAILABLE', 'RESERVED', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    lock_token VARCHAR(100) NULL,
    locked_until TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_slot_time_window CHECK (end_timestamp > start_timestamp)
);

CREATE INDEX IF NOT EXISTS idx_booking_slots_worker_window 
    ON booking_slots(worker_id, slot_date, start_time, end_time);
CREATE INDEX IF NOT EXISTS idx_booking_slots_date_status 
    ON booking_slots(slot_date, status);

-- 5. Extend bookings Table with Dual Booking and Lifecycle Columns
ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS booking_type VARCHAR(20) NOT NULL DEFAULT 'INSTANT',
    ADD COLUMN IF NOT EXISTS scheduled_end_at TIMESTAMP NULL,
    ADD COLUMN IF NOT EXISTS slot_id UUID REFERENCES booking_slots(slot_id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS cancellation_reason TEXT NULL,
    ADD COLUMN IF NOT EXISTS cancelled_by UUID REFERENCES users(user_id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMP NULL,
    ADD COLUMN IF NOT EXISTS worker_assigned_at TIMESTAMP NULL,
    ADD COLUMN IF NOT EXISTS estimated_arrival_minutes INT NULL;

-- Backfill scheduled_end_at for any existing rows (default to scheduled_at + 1 hour)
UPDATE bookings
SET scheduled_end_at = scheduled_at + INTERVAL '1 hour'
WHERE scheduled_end_at IS NULL;

-- 6. Update bookings status constraint to accommodate full state machine
ALTER TABLE bookings DROP CONSTRAINT IF EXISTS bookings_status_check;
ALTER TABLE bookings ADD CONSTRAINT bookings_status_check CHECK (
    status IN (
        'PENDING',
        'REQUESTED',
        'ACCEPTED',
        'CONFIRMED',
        'ON_THE_WAY',
        'ARRIVED',
        'IN_PROGRESS',
        'COMPLETED',
        'CANCELLED',
        'REJECTED',
        'EXPIRED',
        'SEARCHING',
        'WORKER_NOT_FOUND'
    )
);

-- 7. Add Check Constraint for booking_type
ALTER TABLE bookings DROP CONSTRAINT IF EXISTS bookings_booking_type_check;
ALTER TABLE bookings ADD CONSTRAINT bookings_booking_type_check CHECK (
    booking_type IN ('INSTANT', 'SCHEDULED')
);

-- 8. Add Indexes for Dual Booking & Scheduled Time Lookups
CREATE INDEX IF NOT EXISTS idx_bookings_type_status 
    ON bookings(booking_type, status);
CREATE INDEX IF NOT EXISTS idx_bookings_worker_schedule_range 
    ON bookings(worker_id, scheduled_at, scheduled_end_at);

-- 9. Seed Default Weekly Availability for All Existing Workers (Mon-Sat, 08:00 - 20:00, break 13:00 - 14:00)
INSERT INTO worker_availability (worker_id, day_of_week, is_available, start_time, end_time, break_start_time, break_end_time, slot_duration_mins)
SELECT 
    w.worker_id,
    d.day,
    true,
    '08:00:00'::TIME,
    '20:00:00'::TIME,
    '13:00:00'::TIME,
    '14:00:00'::TIME,
    60
FROM workers w
CROSS JOIN (VALUES (1), (2), (3), (4), (5), (6)) AS d(day)
ON CONFLICT (worker_id, day_of_week) DO NOTHING;

