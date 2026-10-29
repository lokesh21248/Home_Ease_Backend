-- ====================================================================
-- V9: Booking event history, transactional outbox, analytics facts
-- History is the authoritative audit trail (same TX as booking writes).
-- Outbox is the Kafka handoff; it is NOT a second source of truth.
-- Analytics tables are derived and never update bookings.status.
-- ====================================================================

CREATE TABLE IF NOT EXISTS booking_event_history (
    event_id          UUID         PRIMARY KEY,
    booking_id        UUID         NOT NULL REFERENCES bookings(booking_id) ON DELETE RESTRICT,
    event_type        VARCHAR(64)  NOT NULL,
    schema_version    INTEGER      NOT NULL DEFAULT 1,
    previous_status   VARCHAR(32),
    new_status        VARCHAR(32),
    actor_type        VARCHAR(32)  NOT NULL,
    actor_id          UUID,
    correlation_id    VARCHAR(64),
    occurred_at       TIMESTAMPTZ  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_booking_event_history_booking_occurred
    ON booking_event_history (booking_id, occurred_at ASC);

CREATE TABLE IF NOT EXISTS booking_event_outbox (
    event_id          UUID         PRIMARY KEY,
    event_type        VARCHAR(64)  NOT NULL,
    aggregate_id      UUID         NOT NULL,
    schema_version    INTEGER      NOT NULL DEFAULT 1,
    payload           JSONB        NOT NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempt_count     INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_error        VARCHAR(500),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at      TIMESTAMPTZ,
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_booking_event_outbox_poll
    ON booking_event_outbox (status, next_attempt_at, created_at);

CREATE INDEX IF NOT EXISTS idx_booking_event_outbox_aggregate
    ON booking_event_outbox (aggregate_id, created_at);

CREATE INDEX IF NOT EXISTS idx_booking_event_outbox_cleanup
    ON booking_event_outbox (status, published_at);

CREATE TABLE IF NOT EXISTS booking_analytics_inbox (
    event_id          UUID         PRIMARY KEY,
    processed_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS booking_analytics_booking_facts (
    booking_id              UUID         PRIMARY KEY REFERENCES bookings(booking_id) ON DELETE RESTRICT,
    service_id              UUID,
    service_pincode         VARCHAR(6),
    created_at              TIMESTAMPTZ,
    accepted_at             TIMESTAMPTZ,
    started_at              TIMESTAMPTZ,
    completed_at            TIMESTAMPTZ,
    cancelled_at            TIMESTAMPTZ,
    current_status          VARCHAR(32),
    payment_status          VARCHAR(32),
    create_to_accept_ms     BIGINT,
    accept_to_start_ms      BIGINT,
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_booking_analytics_facts_created
    ON booking_analytics_booking_facts (created_at);
CREATE INDEX IF NOT EXISTS idx_booking_analytics_facts_pincode
    ON booking_analytics_booking_facts (service_pincode);

CREATE TABLE IF NOT EXISTS booking_analytics_daily (
    day                     DATE         NOT NULL,
    service_pincode         VARCHAR(6)   NOT NULL DEFAULT '',
    created_count           BIGINT       NOT NULL DEFAULT 0,
    accepted_count          BIGINT       NOT NULL DEFAULT 0,
    started_count           BIGINT       NOT NULL DEFAULT 0,
    completed_count         BIGINT       NOT NULL DEFAULT 0,
    cancelled_count         BIGINT       NOT NULL DEFAULT 0,
    payment_success_count   BIGINT       NOT NULL DEFAULT 0,
    payment_failed_count    BIGINT       NOT NULL DEFAULT 0,
    payment_refunded_count  BIGINT       NOT NULL DEFAULT 0,
    create_to_accept_ms_sum BIGINT       NOT NULL DEFAULT 0,
    create_to_accept_n      BIGINT       NOT NULL DEFAULT 0,
    accept_to_start_ms_sum  BIGINT       NOT NULL DEFAULT 0,
    accept_to_start_n       BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (day, service_pincode)
);
