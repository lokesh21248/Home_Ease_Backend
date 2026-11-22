package com.homeease.backend.tracking;

import com.homeease.backend.model.enums.BookingStage;

/**
 * Pure mapping from "what the booking says" + "what the partner's phone is doing" to a {@link TrackingPhase}.
 *
 * <p>The hard rule (spec: never show LIVE without a recent valid fix): {@link Resolution#live()} is true only while
 * the booking is travelling AND the newest accepted fix is no older than {@code liveWindowMs}. A missing, stale or
 * permission-less location can therefore never be presented as live, whatever the booking stage says.</p>
 */
public final class TrackingPhaseResolver {

    /** Partner device GPS state as last reported by the partner app (callers drop reports that are too old). */
    public enum DeviceGpsState {
        OK, PERMISSION_DENIED, GPS_DISABLED, UNKNOWN
    }

    public record Input(BookingStage stage,
                        Long lastFixReceivedAtMs,
                        Double distanceToDestinationM,
                        DeviceGpsState deviceState,
                        long nowMs) {
    }

    public record Resolution(TrackingPhase phase, boolean live, Long lastFixAgeMs) {
    }

    private final long liveWindowMs;
    private final double nearbyRadiusM;

    public TrackingPhaseResolver() {
        this(30_000L, ArrivalGeofence.Config.defaults().nearbyRadiusM());
    }

    public TrackingPhaseResolver(long liveWindowMs, double nearbyRadiusM) {
        this.liveWindowMs = liveWindowMs;
        this.nearbyRadiusM = nearbyRadiusM;
    }

    public long liveWindowMs() {
        return liveWindowMs;
    }

    public Resolution resolve(Input in) {
        BookingStage stage = in.stage();
        Long age = in.lastFixReceivedAtMs() == null ? null : Math.max(0L, in.nowMs() - in.lastFixReceivedAtMs());

        if (stage == null) {
            return new Resolution(TrackingPhase.BOOKING_CONFIRMED, false, age);
        }

        switch (stage) {
            case CANCELLED, EXPIRED, WORKER_NOT_FOUND:
                return new Resolution(TrackingPhase.CANCELLED, false, age);
            case COMPLETED:
                return new Resolution(TrackingPhase.SERVICE_COMPLETED, false, age);
            case IN_PROGRESS:
                return new Resolution(TrackingPhase.SERVICE_STARTED, false, age);
            case ARRIVED:
                return new Resolution(TrackingPhase.ARRIVED, false, age);
            case ACCEPTED, CONFIRMED:
                return new Resolution(TrackingPhase.PARTNER_ASSIGNED, false, age);
            case ON_THE_WAY:
                return resolveTravelling(in, age);
            default:
                // PENDING, REQUESTED, SEARCHING, REJECTED: booked, nobody travelling yet
                return new Resolution(TrackingPhase.BOOKING_CONFIRMED, false, age);
        }
    }

    private Resolution resolveTravelling(Input in, Long age) {
        // The partner's own phone is the most specific explanation when it has told us what is wrong.
        if (in.deviceState() == DeviceGpsState.PERMISSION_DENIED) {
            return new Resolution(TrackingPhase.GPS_PERMISSION_DENIED, false, age);
        }
        if (age == null) {
            return new Resolution(TrackingPhase.LOCATION_UNAVAILABLE, false, null);
        }
        if (age > liveWindowMs) {
            TrackingPhase phase = in.deviceState() == DeviceGpsState.GPS_DISABLED
                    ? TrackingPhase.LOCATION_UNAVAILABLE
                    : TrackingPhase.TRACKING_INTERRUPTED;
            return new Resolution(phase, false, age);
        }
        boolean nearby = in.distanceToDestinationM() != null && in.distanceToDestinationM() <= nearbyRadiusM;
        return new Resolution(nearby ? TrackingPhase.PARTNER_NEARBY : TrackingPhase.PARTNER_EN_ROUTE, true, age);
    }
}
