package com.homeease.backend.tracking;

/**
 * Customer-facing tracking state. It is a <em>projection</em> of the persisted booking stage plus live telemetry,
 * not a second source of truth: the booking's {@code BookingStage} (and its state machine) stays authoritative, so
 * no database enum has to change and the two can never disagree.
 */
public enum TrackingPhase {
    /** Booked, no partner travelling yet (includes searching for a partner). */
    BOOKING_CONFIRMED,
    /** A partner accepted but has not started travelling. */
    PARTNER_ASSIGNED,
    /** Travelling, with a recent valid GPS fix. */
    PARTNER_EN_ROUTE,
    /** Travelling and within the nearby radius of the address. */
    PARTNER_NEARBY,
    ARRIVED,
    SERVICE_STARTED,
    SERVICE_COMPLETED,
    CANCELLED,
    /** Travelling, but no valid location has ever been received (or the device reports GPS switched off). */
    LOCATION_UNAVAILABLE,
    /** Travelling, and the partner's device reports location permission denied. */
    GPS_PERMISSION_DENIED,
    /** Travelling, a location was received before, but updates stopped arriving. */
    TRACKING_INTERRUPTED
}
