package com.homeease.backend.tracking;

import com.homeease.backend.routing.GeoMath;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Decides whether a travelling partner has genuinely arrived at the booking's persisted service address.
 *
 * <p>A single GPS fix inside the radius is never enough (urban GPS jumps tens of metres, and a partner can drive
 * straight past the building). An arrival is confirmed only when ALL of this holds:</p>
 * <ol>
 *   <li>the fix is inside {@code arrivalRadiusM} of the destination;</li>
 *   <li>its reported accuracy is at most {@code maxAccuracyM} (an unknown accuracy is allowed, a poor one is
 *       ignored: it neither confirms nor cancels an arrival in progress);</li>
 *   <li>the reported speed is at most {@code maxSpeedMps} (a partner passing at road speed has not arrived);</li>
 *   <li>{@code requiredFixes} consecutive qualifying fixes, strictly increasing in time, spanning at least
 *       {@code minDwellMs}.</li>
 * </ol>
 * A good-accuracy fix outside the radius, or a too-fast fix inside it, restarts the count.
 *
 * <p>State is one small streak per booking (bounded LRU), so memory stays flat across thousands of bookings.
 * Time comes from the fix itself; the class never reads a clock.</p>
 */
public final class ArrivalGeofence {

    public enum Verdict {
        /** Beyond the nearby radius. */
        OUTSIDE,
        /** Within the nearby radius but not (yet) inside the arrival radius. */
        NEARBY,
        /** Inside the arrival radius; counting consecutive qualifying fixes. */
        INSIDE_CONFIRMING,
        /** Arrival confirmed. The caller changes the booking state and then calls {@link #reset}. */
        ARRIVED,
        /** Not evaluated: accuracy too poor, or an old/duplicate fix. State unchanged. */
        IGNORED
    }

    public record Evaluation(Verdict verdict, double distanceMeters, int consecutiveFixes) {
    }

    public record Config(double arrivalRadiusM, double nearbyRadiusM, double maxAccuracyM,
                         int requiredFixes, long minDwellMs, double maxSpeedMps) {
        public static Config defaults() {
            return new Config(75.0, 500.0, 40.0, 3, 6_000L, 4.0);
        }
    }

    private static final int MAX_TRACKED = 5_000;

    private static final class Streak {
        int count;
        long firstTs;
        long lastTs;
    }

    private final Config cfg;
    private final Map<UUID, Streak> streaks = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Streak> eldest) {
                    return size() > MAX_TRACKED;
                }
            });

    public ArrivalGeofence() {
        this(Config.defaults());
    }

    public ArrivalGeofence(Config cfg) {
        this.cfg = cfg;
    }

    public Config config() {
        return cfg;
    }

    public Evaluation evaluate(UUID bookingId, GpsFix fix, double destLat, double destLng) {
        double distance = GeoMath.haversineMeters(fix.lat(), fix.lng(), destLat, destLng);
        boolean poorAccuracy = fix.accuracyM() != null && fix.accuracyM() > cfg.maxAccuracyM();

        synchronized (streaks) {
            Streak s = streaks.get(bookingId);

            if (s != null && fix.timestampMs() <= s.lastTs) {
                return new Evaluation(Verdict.IGNORED, distance, s.count);          // replay / out-of-order
            }
            if (poorAccuracy) {
                return new Evaluation(Verdict.IGNORED, distance, s == null ? 0 : s.count);
            }

            boolean inside = distance <= cfg.arrivalRadiusM();
            boolean tooFast = fix.speedMps() != null && fix.speedMps() > cfg.maxSpeedMps();

            if (!inside || tooFast) {
                if (s != null) {
                    streaks.remove(bookingId);                                       // streak broken
                }
                Verdict v = distance <= cfg.nearbyRadiusM() ? Verdict.NEARBY : Verdict.OUTSIDE;
                return new Evaluation(v, distance, 0);
            }

            if (s == null) {
                s = new Streak();
                s.firstTs = fix.timestampMs();
                streaks.put(bookingId, s);
            }
            s.count++;
            s.lastTs = fix.timestampMs();

            boolean confirmed = s.count >= cfg.requiredFixes() && (s.lastTs - s.firstTs) >= cfg.minDwellMs();
            return new Evaluation(confirmed ? Verdict.ARRIVED : Verdict.INSIDE_CONFIRMING, distance, s.count);
        }
    }

    /** Forget a booking (after arrival, cancellation or completion). */
    public void reset(UUID bookingId) {
        streaks.remove(bookingId);
    }

    int trackedBookings() {
        return streaks.size();
    }
}
