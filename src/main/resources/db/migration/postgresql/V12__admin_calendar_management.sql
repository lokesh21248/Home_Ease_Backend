-- =========================================================================
-- V12: Admin Calendar Management Module
-- HomeEase Dual Booking & Calendar Management Architecture
-- Includes:
--   1. booking_settings (slot duration, buffer, advance limit, notice, capacity)
--   2. business_working_hours (Mon-Sun working hours, breaks, closed days)
--   3. calendar_date_overrides (holidays, special dates, custom hours, blocked windows)
--   4. booking_audit_logs (immutable audit log for admin operations)
-- =========================================================================

-- 1. BOOKING SETTINGS
CREATE TABLE IF NOT EXISTS booking_settings (
    setting_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id UUID REFERENCES services(service_id) ON DELETE CASCADE,
    slot_duration_mins INT NOT NULL DEFAULT 60,
    buffer_duration_mins INT NOT NULL DEFAULT 15,
    advance_booking_days INT NOT NULL DEFAULT 14,
    min_booking_notice_mins INT NOT NULL DEFAULT 30,
    max_booking_capacity_per_slot INT NOT NULL DEFAULT 10,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_booking_settings_global
ON booking_settings ((1)) WHERE service_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_booking_settings_service
ON booking_settings (service_id) WHERE service_id IS NOT NULL;

-- Seed default global booking settings
INSERT INTO booking_settings (
    setting_id,
    service_id,
    slot_duration_mins,
    buffer_duration_mins,
    advance_booking_days,
    min_booking_notice_mins,
    max_booking_capacity_per_slot,
    is_active
) VALUES (
    gen_random_uuid(),
    NULL,
    60,
    15,
    14,
    30,
    10,
    true
) ON CONFLICT DO NOTHING;


-- 2. BUSINESS WORKING HOURS
CREATE TABLE IF NOT EXISTS business_working_hours (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id UUID REFERENCES services(service_id) ON DELETE CASCADE,
    day_of_week INT NOT NULL CHECK (day_of_week BETWEEN 1 AND 7), -- 1 = Monday, 7 = Sunday
    is_open BOOLEAN NOT NULL DEFAULT true,
    open_time TIME NOT NULL DEFAULT '08:00:00',
    close_time TIME NOT NULL DEFAULT '20:00:00',
    break_start_time TIME DEFAULT '13:00:00',
    break_end_time TIME DEFAULT '14:00:00',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_business_hours_global_day
ON business_working_hours (day_of_week) WHERE service_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_business_hours_service_day
ON business_working_hours (service_id, day_of_week) WHERE service_id IS NOT NULL;

-- Seed default global business working hours (Mon-Sat 08:00 to 20:00, Sun closed)
INSERT INTO business_working_hours (id, service_id, day_of_week, is_open, open_time, close_time, break_start_time, break_end_time)
VALUES
    (gen_random_uuid(), NULL, 1, true,  '08:00:00', '20:00:00', '13:00:00', '14:00:00'),
    (gen_random_uuid(), NULL, 2, true,  '08:00:00', '20:00:00', '13:00:00', '14:00:00'),
    (gen_random_uuid(), NULL, 3, true,  '08:00:00', '20:00:00', '13:00:00', '14:00:00'),
    (gen_random_uuid(), NULL, 4, true,  '08:00:00', '20:00:00', '13:00:00', '14:00:00'),
    (gen_random_uuid(), NULL, 5, true,  '08:00:00', '20:00:00', '13:00:00', '14:00:00'),
    (gen_random_uuid(), NULL, 6, true,  '08:00:00', '20:00:00', '13:00:00', '14:00:00'),
    (gen_random_uuid(), NULL, 7, false, '08:00:00', '20:00:00', NULL, NULL)
ON CONFLICT DO NOTHING;


-- 3. CALENDAR DATE OVERRIDES
CREATE TABLE IF NOT EXISTS calendar_date_overrides (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id UUID REFERENCES services(service_id) ON DELETE CASCADE,
    override_date DATE NOT NULL,
    override_type VARCHAR(50) NOT NULL, -- BLOCKED_DATE, PUBLIC_HOLIDAY, FESTIVAL_HOLIDAY, SPECIAL_WORKING_DAY, CUSTOM_HOURS
    is_closed BOOLEAN NOT NULL DEFAULT true,
    custom_open_time TIME,
    custom_close_time TIME,
    blocked_start_time TIME,
    blocked_end_time TIME,
    reason TEXT,
    created_by UUID REFERENCES users(user_id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_calendar_overrides_date
ON calendar_date_overrides (override_date);

CREATE INDEX IF NOT EXISTS idx_calendar_overrides_type
ON calendar_date_overrides (override_type);

CREATE INDEX IF NOT EXISTS idx_calendar_overrides_service_date
ON calendar_date_overrides (service_id, override_date);


-- 4. BOOKING AUDIT LOGS
CREATE TABLE IF NOT EXISTS booking_audit_logs (
    log_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id UUID REFERENCES bookings(booking_id) ON DELETE CASCADE,
    entity_type VARCHAR(50) NOT NULL DEFAULT 'BOOKING', -- BOOKING, CALENDAR_OVERRIDE, WORKING_HOURS, SETTINGS
    entity_id VARCHAR(100),
    admin_id UUID REFERENCES users(user_id) ON DELETE SET NULL,
    admin_name VARCHAR(100),
    action VARCHAR(100) NOT NULL, -- RESCHEDULE_BOOKING, ASSIGN_WORKER, REASSIGN_WORKER, UPDATE_WORKING_HOURS, CREATE_DATE_OVERRIDE, DELETE_DATE_OVERRIDE, UPDATE_SETTINGS
    previous_value TEXT,
    updated_value TEXT,
    reason TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_booking_audit_logs_booking
ON booking_audit_logs (booking_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_booking_audit_logs_entity
ON booking_audit_logs (entity_type, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_booking_audit_logs_admin
ON booking_audit_logs (admin_id, created_at DESC);

