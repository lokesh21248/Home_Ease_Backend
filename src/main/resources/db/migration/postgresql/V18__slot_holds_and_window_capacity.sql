-- ====================================================================
-- HomeEase PostgreSQL Migration V18: Slot Holds & Window Capacity
-- Implements Phase 1: Slot booking, concurrency protection,
--                     window capacity configuration, and hold sweeper
-- ====================================================================

-- 1. Add window_key and hold columns to booking_slots if not present
ALTER TABLE booking_slots ADD COLUMN IF NOT EXISTS window_key VARCHAR(150);
ALTER TABLE booking_slots ADD COLUMN IF NOT EXISTS hold_token VARCHAR(100);

CREATE INDEX IF NOT EXISTS idx_booking_slots_window_active 
    ON booking_slots (window_key, status, locked_until);

CREATE INDEX IF NOT EXISTS idx_booking_slots_sweeper 
    ON booking_slots (status, locked_until);

-- 2. Slot Window Capacity Configuration Table
CREATE TABLE IF NOT EXISTS slot_window_capacity (
    capacity_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id UUID REFERENCES services(service_id) ON DELETE CASCADE,
    zone_pincode VARCHAR(10),
    day_of_week INT NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    max_capacity INT NOT NULL DEFAULT 3,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_slot_window_cap UNIQUE (service_id, zone_pincode, day_of_week, start_time)
);

CREATE INDEX IF NOT EXISTS idx_slot_window_cap_lookup 
    ON slot_window_capacity (service_id, zone_pincode, day_of_week, start_time);

-- 3. Seed default window capacity of 3 for testing (50 parallel holds on capacity 3 -> exactly 3 succeed)
INSERT INTO slot_window_capacity (service_id, zone_pincode, day_of_week, start_time, end_time, max_capacity, is_active)
SELECT 
    s.service_id,
    '500072',
    d.day,
    t.start_time,
    t.end_time,
    3,
    true
FROM services s
CROSS JOIN (SELECT generate_series(1, 7) AS day) d
CROSS JOIN (
    VALUES 
        ('09:00:00'::time, '10:00:00'::time),
        ('10:00:00'::time, '11:00:00'::time),
        ('11:00:00'::time, '12:00:00'::time),
        ('12:00:00'::time, '13:00:00'::time),
        ('14:00:00'::time, '15:00:00'::time),
        ('15:00:00'::time, '16:00:00'::time),
        ('16:00:00'::time, '17:00:00'::time),
        ('17:00:00'::time, '18:00:00'::time),
        ('18:00:00'::time, '19:00:00'::time)
) AS t(start_time, end_time)
ON CONFLICT (service_id, zone_pincode, day_of_week, start_time) DO UPDATE 
SET max_capacity = EXCLUDED.max_capacity;

-- 4. Payment Reconciliation Audit Table
CREATE TABLE IF NOT EXISTS payment_reconciliation_audit (
    audit_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_run_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    orders_checked INT NOT NULL DEFAULT 0,
    discrepancies_found INT NOT NULL DEFAULT 0,
    resolved_count INT NOT NULL DEFAULT 0,
    details JSONB,
    status VARCHAR(20) NOT NULL DEFAULT 'SUCCESS'
);

