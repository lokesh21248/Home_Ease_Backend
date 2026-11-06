-- ============================================================================
-- V19: Phase 2 Assignment Engine, Weights Config, and Reassignment Triggers
-- ============================================================================

-- 1. Add score and ranking order to booking_assignment table
ALTER TABLE booking_assignment
    ADD COLUMN IF NOT EXISTS score DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS rank_order INT;

-- 2. Non-negotiable Rule #10: Weights, radii, timeouts, thresholds live in DB config per zone/category
CREATE TABLE IF NOT EXISTS assignment_weight_config (
    config_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id UUID REFERENCES services(service_id) ON DELETE CASCADE,
    zone_pincode VARCHAR(20),
    distance_weight DOUBLE PRECISION NOT NULL DEFAULT 0.35,
    rating_weight DOUBLE PRECISION NOT NULL DEFAULT 0.25,
    acceptance_rate_weight DOUBLE PRECISION NOT NULL DEFAULT 0.20,
    completion_rate_weight DOUBLE PRECISION NOT NULL DEFAULT 0.10,
    fairness_weight DOUBLE PRECISION NOT NULL DEFAULT 0.10,
    max_search_radius_meters DOUBLE PRECISION NOT NULL DEFAULT 8000.0,
    offer_timeout_seconds INT NOT NULL DEFAULT 60,
    batch_assign_t_minus_minutes INT NOT NULL DEFAULT 120,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_assign_weight_svc_pin
    ON assignment_weight_config (service_id, zone_pincode);

-- Seed default global configuration
INSERT INTO assignment_weight_config (
    config_id,
    service_id,
    zone_pincode,
    distance_weight,
    rating_weight,
    acceptance_rate_weight,
    completion_rate_weight,
    fairness_weight,
    max_search_radius_meters,
    offer_timeout_seconds,
    batch_assign_t_minus_minutes
) VALUES (
    'a0000000-0000-0000-0000-000000000001',
    NULL,
    NULL,
    0.35,
    0.25,
    0.20,
    0.10,
    0.10,
    8000.0,
    60,
    120
) ON CONFLICT (config_id) DO NOTHING;

-- 3. Reassignment triggers table (Section 7)
CREATE TABLE IF NOT EXISTS reassignment_trigger_log (
    trigger_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id UUID NOT NULL REFERENCES bookings(booking_id) ON DELETE CASCADE,
    previous_worker_id UUID REFERENCES workers(worker_id) ON DELETE SET NULL,
    new_worker_id UUID REFERENCES workers(worker_id) ON DELETE SET NULL,
    trigger_type VARCHAR(50) NOT NULL,
    reason TEXT,
    triggered_by VARCHAR(50) NOT NULL DEFAULT 'SYSTEM',
    attempt_number INT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_reassign_booking
    ON reassignment_trigger_log (booking_id);

CREATE INDEX IF NOT EXISTS idx_reassign_type_created
    ON reassignment_trigger_log (trigger_type, created_at);

