package com.homeease.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Response contract for {@code GET /api/v1/tracking/{bookingId}/route}.
 *
 * <p>Every numeric value is provider data (distance, duration, geometry) or a persisted/recorded coordinate.
 * Nothing here is estimated by the server. {@code polyline} is a Google encoded polyline, precision 1e5,
 * points ordered {@code latitude, longitude}.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BookingRouteResponse(
        UUID bookingId,
        /** Booking status at the time of the call. */
        String status,
        boolean found,
        String provider,
        String polyline,
        String polylineEncoding,
        long distanceMeters,
        long durationSeconds,
        /** Duration without live traffic, when the provider supplies it. */
        Long staticDurationSeconds,
        /** The worker GPS fix the route was computed from. */
        GeoPoint origin,
        /** The booking's persisted service-address coordinates. */
        GeoPoint destination,
        Instant calculatedAt,
        long calculatedAtEpochMs,
        /** true when served from the per-booking cache (no provider call was made). */
        boolean cached,
        /** Hint: the earliest sensible time (seconds) for the client to ask for a periodic refresh. */
        int refreshAfterSeconds) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GeoPoint(double lat, double lng, Long recordedAtEpochMs) {
    }
}
