-- ====================================================================
-- V27: Durable live-tracking history (sampled partner GPS breadcrumbs per booking)
-- ====================================================================
-- Redis holds only the LATEST location (short TTL) for the live map. This table is the permanent, queryable record
-- of where the partner travelled for a booking: dispute resolution, arrival audit, ETA analysis.
--
-- Written by the core service from the tracking service's accepted fixes, sampled (time/distance based) and batched,
-- so the volume is a few rows per booking-minute, not one row per GPS tick.
-- The booking FK cascades so deleting a booking removes its breadcrumbs. Safe to re-run.

CREATE TABLE IF NOT EXISTS booking_location_history (
    id           BIGSERIAL PRIMARY KEY,
    booking_id   UUID NOT NULL REFERENCES bookings(booking_id) ON DELETE CASCADE,
    worker_id    UUID,
    lat          DOUBLE PRECISION NOT NULL CHECK (lat BETWEEN -90 AND 90),
    lng          DOUBLE PRECISION NOT NULL CHECK (lng BETWEEN -180 AND 180),
    accuracy_m   REAL,
    speed_mps    REAL,
    heading_deg  REAL,
    -- device fix time (partner phone clock) and server receipt time (authoritative for "how fresh")
    recorded_at  TIMESTAMPTZ NOT NULL,
    received_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_booking_location_history_booking_time
    ON booking_location_history (booking_id, recorded_at);

-- retention purge scans by age
CREATE INDEX IF NOT EXISTS idx_booking_location_history_received
    ON booking_location_history (received_at);
