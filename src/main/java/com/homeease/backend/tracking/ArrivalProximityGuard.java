package com.homeease.backend.tracking;

import com.homeease.backend.exception.TrackingApiException;
import com.homeease.backend.exception.TrackingApiException.Code;
import com.homeease.backend.model.entity.Booking;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.routing.GeoMath;
import com.homeease.backend.tracking.LatestFixReader.LatestFix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;

/**
 * Server-side check behind the partner's manual "Arrived" button. Before this existed, any assigned partner could
 * mark ARRIVED from anywhere, which also issues the customer's doorstep PIN. Now the partner's latest fresh GPS fix
 * must be within {@code homeease.arrival.manual-radius-meters} of the booking's persisted address.
 *
 * <p>The manual radius (default 200 m) is deliberately wider than the automatic geofence (75 m): a partner standing
 * at the gate of a large complex should still be able to confirm. Bookings that have no usable stored coordinates
 * cannot be checked and are allowed (logged), so a data gap never blocks a job in the field.</p>
 */
@Component
public class ArrivalProximityGuard {

    private static final Logger log = LoggerFactory.getLogger(ArrivalProximityGuard.class);

    private final LatestFixReader fixReader;
    private final Clock clock;
    private final boolean enabled;
    private final double manualRadiusM;
    private final long maxFixAgeMs;

    @org.springframework.beans.factory.annotation.Autowired
    public ArrivalProximityGuard(LatestFixReader fixReader,
                                 @Value("${homeease.arrival.require-proximity:true}") boolean enabled,
                                 @Value("${homeease.arrival.manual-radius-meters:200}") double manualRadiusM,
                                 @Value("${homeease.arrival.max-fix-age-seconds:120}") long maxFixAgeSeconds) {
        this(fixReader, Clock.systemUTC(), enabled, manualRadiusM, maxFixAgeSeconds);
    }

    ArrivalProximityGuard(LatestFixReader fixReader, Clock clock, boolean enabled, double manualRadiusM,
                          long maxFixAgeSeconds) {
        this.fixReader = fixReader;
        this.clock = clock;
        this.enabled = enabled;
        this.manualRadiusM = manualRadiusM;
        this.maxFixAgeMs = maxFixAgeSeconds * 1000L;
    }

    /** @throws TrackingApiException LOCATION_REQUIRED or NOT_AT_DESTINATION */
    public void checkManualArrival(Booking booking) {
        if (!enabled || booking == null) {
            return;
        }
        BookingStage s = booking.getStatus();
        if (s == BookingStage.ARRIVED || s == BookingStage.IN_PROGRESS || s == BookingStage.COMPLETED) {
            return; // idempotent re-submit: the service layer answers it without changing anything
        }
        if (!GeoMath.isUsableCoordinate(booking.getUserLat(), booking.getUserLng())) {
            log.warn("Manual arrival for booking {} not proximity-checked: booking has no usable coordinates",
                    booking.getBookingId());
            return;
        }
        Optional<LatestFix> fix = fixReader.read(booking.getBookingId());
        long now = clock.millis();
        if (fix.isEmpty() || now - fix.get().receivedAtMs() > maxFixAgeMs) {
            throw new TrackingApiException(Code.LOCATION_REQUIRED,
                    "We could not confirm your location. Turn on GPS and live tracking, wait a few seconds, then try again.", 5);
        }
        double d = GeoMath.haversineMeters(fix.get().lat(), fix.get().lng(), booking.getUserLat(), booking.getUserLng());
        if (d > manualRadiusM) {
            throw new TrackingApiException(Code.NOT_AT_DESTINATION,
                    "You are about " + formatDistance(d) + " from the customer's address. Mark arrival when you reach it.");
        }
    }

    private static String formatDistance(double meters) {
        return meters >= 1000 ? String.format(java.util.Locale.US, "%.1f km", meters / 1000.0)
                : Math.round(meters) + " m";
    }
}
