package com.homeease.backend.tracking;

import com.homeease.backend.routing.GeoMath;

/**
 * Pure, stateless validation of a raw GPS report. No I/O and no clock of its own ({@code nowMs} is passed in), so
 * every rule is deterministic and unit-testable.
 *
 * <p>Rules, in order: finite in-range usable coordinates (not the 0,0 placeholder); sane accuracy; sane speed;
 * a device timestamp that is present, not in the future and not stale; and, when the previous accepted fix is
 * known, strict time ordering (replays/duplicates rejected) and a physically possible displacement.</p>
 */
public final class GpsFixValidator {

    public enum Reason {
        INVALID_COORDINATES,
        INVALID_ACCURACY,
        ACCURACY_TOO_LOW,
        INVALID_SPEED,
        MISSING_TIMESTAMP,
        TIMESTAMP_IN_FUTURE,
        TIMESTAMP_TOO_OLD,
        OUT_OF_ORDER,
        IMPOSSIBLE_MOVEMENT
    }

    /** Outcome: either an accepted normalised fix, or a rejection with a stable reason. */
    public record Result(GpsFix fix, Reason reason, String message) {
        public boolean accepted() {
            return fix != null;
        }

        static Result ok(GpsFix fix) {
            return new Result(fix, null, null);
        }

        static Result reject(Reason reason, String message) {
            return new Result(null, reason, message);
        }
    }

    /** Raw values as received from a client (nullable, unvalidated). */
    public record Raw(Double lat, Double lng, Long timestampMs, Double accuracyM, Double speedMps, Double headingDeg) {
    }

    private final double maxAccuracyM;
    private final double maxSpeedMps;
    private final long maxFutureSkewMs;
    private final long maxAgeMs;
    private final double teleportMinDistanceM;
    private final double teleportSpeedMps;

    public GpsFixValidator() {
        this(50.0, 70.0, 5_000L, 120_000L, 300.0, 55.0);
    }

    public GpsFixValidator(double maxAccuracyM, double maxSpeedMps, long maxFutureSkewMs, long maxAgeMs,
                           double teleportMinDistanceM, double teleportSpeedMps) {
        this.maxAccuracyM = maxAccuracyM;
        this.maxSpeedMps = maxSpeedMps;
        this.maxFutureSkewMs = maxFutureSkewMs;
        this.maxAgeMs = maxAgeMs;
        this.teleportMinDistanceM = teleportMinDistanceM;
        this.teleportSpeedMps = teleportSpeedMps;
    }

    /**
     * @param previous the last ACCEPTED fix for the same booking, or {@code null}
     * @param nowMs    server wall clock (epoch millis)
     */
    public Result validate(Raw raw, GpsFix previous, long nowMs) {
        if (raw == null || !GeoMath.isUsableCoordinate(raw.lat(), raw.lng())) {
            return Result.reject(Reason.INVALID_COORDINATES,
                    "Latitude/longitude must be finite, within range and not the 0,0 placeholder.");
        }

        Double accuracy = raw.accuracyM();
        if (accuracy != null) {
            if (!Double.isFinite(accuracy) || accuracy < 0) {
                return Result.reject(Reason.INVALID_ACCURACY, "Accuracy must be a non-negative number of metres.");
            }
            if (accuracy > maxAccuracyM) {
                return Result.reject(Reason.ACCURACY_TOO_LOW,
                        "Accuracy " + Math.round(accuracy) + " m is worse than the allowed " + Math.round(maxAccuracyM) + " m.");
            }
        }

        Double speed = raw.speedMps();
        if (speed != null) {
            if (!Double.isFinite(speed)) {
                return Result.reject(Reason.INVALID_SPEED, "Speed must be a finite number.");
            }
            // Android reports -1 for "unknown"; treat any negative value as unknown rather than as a fault.
            if (speed < 0) {
                speed = null;
            } else if (speed > maxSpeedMps) {
                return Result.reject(Reason.INVALID_SPEED, "Speed " + Math.round(speed) + " m/s is not plausible.");
            }
        }

        Long ts = raw.timestampMs();
        if (ts == null || ts <= 0) {
            return Result.reject(Reason.MISSING_TIMESTAMP, "A GPS fix timestamp (epoch milliseconds) is required.");
        }
        if (ts > nowMs + maxFutureSkewMs) {
            return Result.reject(Reason.TIMESTAMP_IN_FUTURE,
                    "Fix time is " + ((ts - nowMs) / 1000) + " s ahead of the server clock.");
        }
        if (nowMs - ts > maxAgeMs) {
            return Result.reject(Reason.TIMESTAMP_TOO_OLD,
                    "Fix is " + ((nowMs - ts) / 1000) + " s old; the maximum is " + (maxAgeMs / 1000) + " s.");
        }

        if (previous != null) {
            if (ts <= previous.timestampMs()) {
                return Result.reject(Reason.OUT_OF_ORDER, "Fix is not newer than the last accepted fix.");
            }
            double dist = GeoMath.haversineMeters(previous.lat(), previous.lng(), raw.lat(), raw.lng());
            double seconds = (ts - previous.timestampMs()) / 1000.0;
            if (dist > teleportMinDistanceM && dist / seconds > teleportSpeedMps) {
                return Result.reject(Reason.IMPOSSIBLE_MOVEMENT,
                        "Moved " + Math.round(dist) + " m in " + Math.round(seconds) + " s.");
            }
        }

        Double heading = raw.headingDeg();
        if (heading != null) {
            if (!Double.isFinite(heading) || heading < 0) {
                heading = null; // -1 / NaN = unknown
            } else {
                heading = heading % 360.0;
            }
        }

        return Result.ok(new GpsFix(raw.lat(), raw.lng(), ts, accuracy, speed, heading));
    }
}
