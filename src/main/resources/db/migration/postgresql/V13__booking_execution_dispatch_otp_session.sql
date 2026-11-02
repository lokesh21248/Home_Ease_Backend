-- ====================================================================
-- HomeEase PostgreSQL Migration V13: Booking Execution & Dispatch Pipeline
-- Combines: booking_assignment, booking_otp, service_session,
--           notification_delivery, booking_events
-- ====================================================================

-- 1. booking_assignment -------------------------------------------------
-- One row per (booking, worker) candidate request batch.
CREATE TABLE IF NOT EXISTS booking_assignment (
    assignment_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id     UUID NOT NULL REFERENCES bookings(booking_id) ON DELETE CASCADE,
    worker_id      UUID NOT NULL REFERENCES workers(worker_id) ON DELETE RESTRICT,
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'TIMEOUT', 'DROPPED', 'EXPIRED')),
    attempt_no     INTEGER NOT NULL DEFAULT 0,
    requested_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    responded_at   TIMESTAMPTZ,
    accepted_at    TIMESTAMPTZ,
    expires_at     TIMESTAMPTZ NOT NULL,
    version        INTEGER NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_booking_assignment_booking
    ON booking_assignment (booking_id);
CREATE INDEX IF NOT EXISTS idx_booking_assignment_status_expires
    ON booking_assignment (status, expires_at);
CREATE INDEX IF NOT EXISTS idx_booking_assignment_worker_booking
    ON booking_assignment (worker_id, booking_id);

-- 2. booking_otp ----------------------------------------------------------
-- Customer 4-digit OTP. Only SHA-256 hash is stored; never plaintext.
CREATE TABLE IF NOT EXISTS booking_otp (
    otp_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id        UUID NOT NULL REFERENCES bookings(booking_id) ON DELETE CASCADE,
    hashed_otp        VARCHAR(64) NOT NULL,
    phone_hash        VARCHAR(64) NOT NULL,
    expires_at        TIMESTAMPTZ NOT NULL,
    max_attempts      INTEGER NOT NULL DEFAULT 5,
    attempts          INTEGER NOT NULL DEFAULT 0,
    resend_cooldown_until TIMESTAMPTZ,
    status            VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'USED', 'EXPIRED', 'REVOKED')),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_booking_otp_booking
    ON booking_otp (booking_id);
CREATE INDEX IF NOT EXISTS idx_booking_otp_expires
    ON booking_otp (expires_at);
CREATE INDEX IF NOT EXISTS idx_booking_otp_status
    ON booking_otp (status);
CREATE INDEX IF NOT EXISTS idx_booking_otp_booking_status
    ON booking_otp (booking_id, status);

-- 3. service_session -----------------------------------------------------
-- Backend-authoritative service timer. start/completion times are written
-- only by the backend at exact UTC instants; clients recalculate elapsed
-- time from these, never from device clocks.
CREATE TABLE IF NOT EXISTS service_session (
    session_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id       UUID NOT NULL REFERENCES bookings(booking_id) ON DELETE CASCADE,
    worker_id        UUID NOT NULL REFERENCES workers(worker_id) ON DELETE RESTRICT,
    customer_id      UUID NOT NULL REFERENCES users(user_id) ON DELETE RESTRICT,
    started_at       TIMESTAMPTZ NOT NULL,
    completed_at     TIMESTAMPTZ,
    duration_minutes NUMERIC(10, 2),
    final_price      NUMERIC(10, 2),
    status           VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED')),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_service_session_booking'
    ) THEN
        ALTER TABLE service_session ADD CONSTRAINT uq_service_session_booking UNIQUE (booking_id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_service_session_booking
    ON service_session (booking_id);
CREATE INDEX IF NOT EXISTS idx_service_session_status
    ON service_session (status, started_at);
CREATE INDEX IF NOT EXISTS idx_service_session_worker
    ON service_session (worker_id, started_at);

-- 4. notification_delivery -------------------------------------------------
-- Per-recipient delivery tracking for every booking lifecycle notification.
CREATE TABLE IF NOT EXISTS notification_delivery (
    delivery_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id        UUID REFERENCES bookings(booking_id) ON DELETE SET NULL,
    user_id           UUID,
    worker_id         UUID,
    channel           VARCHAR(20) NOT NULL
        CHECK (channel IN ('FCM', 'WS')),
    topic_destination VARCHAR(255),
    attempt_no        INTEGER NOT NULL DEFAULT 0,
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'DROPPED')),
    error_message     VARCHAR(500),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sent_at           TIMESTAMPTZ,
    delivered_at      TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_notification_delivery_booking
    ON notification_delivery (booking_id);
CREATE INDEX IF NOT EXISTS idx_notification_delivery_status_attempt
    ON notification_delivery (status, attempt_no, created_at);

-- 5. booking_events ---------------------------------------------------------
-- Event dissemination log for auditability.
CREATE TABLE IF NOT EXISTS booking_events (
    event_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id        UUID NOT NULL REFERENCES bookings(booking_id) ON DELETE CASCADE,
    event_type        VARCHAR(64) NOT NULL,
    payload           JSONB NOT NULL,
    published_at      TIMESTAMPTZ,
    consumed_by       VARCHAR(100),
    delivery_status   VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (delivery_status IN ('PENDING', 'SENT', 'FAILED', 'DLT')),
    attempt_no        INTEGER NOT NULL DEFAULT 0,
    error_message     VARCHAR(500),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_booking_events_booking
    ON booking_events (booking_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_booking_events_consumed
    ON booking_events (consumed_by, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_booking_events_status
    ON booking_events (delivery_status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_booking_events_type
    ON booking_events (event_type, created_at DESC);

-- 6. Add arrived_at, started_at, completed_at, final_price to bookings if not present
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'bookings' AND column_name = 'arrived_at') THEN
        ALTER TABLE bookings ADD COLUMN arrived_at TIMESTAMPTZ;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'bookings' AND column_name = 'final_price') THEN
        ALTER TABLE bookings ADD COLUMN final_price NUMERIC(10, 2);
    END IF;
END $$;

