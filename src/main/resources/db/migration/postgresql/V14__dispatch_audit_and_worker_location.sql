-- ====================================================================
-- HomeEase PostgreSQL Migration V14: Dispatch Audit Logs & Worker Location
-- ====================================================================

-- 1. Add last_location_updated_at to workers table
ALTER TABLE workers ADD COLUMN IF NOT EXISTS last_location_updated_at TIMESTAMPTZ;

-- 2. dispatch_audit_logs table for Task 8 diagnostics
CREATE TABLE IF NOT EXISTS dispatch_audit_logs (
    log_id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id              UUID NOT NULL REFERENCES bookings(booking_id) ON DELETE CASCADE,
    service_id              UUID,
    customer_lat            DOUBLE PRECISION,
    customer_lng            DOUBLE PRECISION,
    search_radius_meters    DOUBLE PRECISION NOT NULL DEFAULT 5000.0,
    eligible_workers_count  INTEGER NOT NULL DEFAULT 0,
    rejected_worker_reasons JSONB,
    selected_worker_id      UUID,
    kafka_status            VARCHAR(50),
    websocket_status        VARCHAR(50),
    fcm_status              VARCHAR(50),
    assignment_status       VARCHAR(50),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_dispatch_audit_logs_booking
    ON dispatch_audit_logs (booking_id);
CREATE INDEX IF NOT EXISTS idx_dispatch_audit_logs_created
    ON dispatch_audit_logs (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_dispatch_audit_logs_service
    ON dispatch_audit_logs (service_id);

