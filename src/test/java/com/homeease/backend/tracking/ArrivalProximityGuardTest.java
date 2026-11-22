package com.homeease.backend.tracking;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.homeease.backend.exception.TrackingApiException;
import com.homeease.backend.model.entity.Booking;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.tracking.LatestFixReader.LatestFix;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ArrivalProximityGuardTest {

    static final double DEST_LAT = 17.4486;
    static final double DEST_LNG = 78.3908;
    static final long NOW = 1_800_000_000_000L;

    final LatestFixReader reader = mock(LatestFixReader.class);
    final ArrivalProximityGuard guard = new ArrivalProximityGuard(reader,
            Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC), true, 200.0, 120);

    private Booking booking(BookingStage stage, Double lat, Double lng) {
        return Booking.builder().bookingId(UUID.randomUUID()).status(stage).userLat(lat).userLng(lng).build();
    }

    private void fixAt(Booking b, double northMeters, long ageMs) {
        when(reader.read(b.getBookingId())).thenReturn(Optional.of(new LatestFix(
                DEST_LAT + northMeters / 111_195.0, DEST_LNG, NOW - ageMs, NOW - ageMs, 6.0, 0.0, 0.0, true)));
    }

    private void assertCode(Runnable r, TrackingApiException.Code code) {
        assertThatThrownBy(r::run).isInstanceOf(TrackingApiException.class)
                .extracting(e -> ((TrackingApiException) e).getCode()).isEqualTo(code);
    }

    @Test
    @DisplayName("a partner 3 km away cannot mark arrival (it would issue the customer's PIN)")
    void farAwayIsRejected() {
        Booking b = booking(BookingStage.ON_THE_WAY, DEST_LAT, DEST_LNG);
        fixAt(b, 3_000, 2_000);
        assertCode(() -> guard.checkManualArrival(b), TrackingApiException.Code.NOT_AT_DESTINATION);
    }

    @Test
    @DisplayName("within 200 m with a fresh fix is allowed")
    void nearIsAllowed() {
        Booking b = booking(BookingStage.ON_THE_WAY, DEST_LAT, DEST_LNG);
        fixAt(b, 150, 2_000);
        assertThatCode(() -> guard.checkManualArrival(b)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("no fix, or a fix older than 120 s, cannot prove presence")
    void missingOrStaleFix() {
        Booking b = booking(BookingStage.ON_THE_WAY, DEST_LAT, DEST_LNG);
        when(reader.read(b.getBookingId())).thenReturn(Optional.empty());
        assertCode(() -> guard.checkManualArrival(b), TrackingApiException.Code.LOCATION_REQUIRED);

        fixAt(b, 10, 121_000);
        assertCode(() -> guard.checkManualArrival(b), TrackingApiException.Code.LOCATION_REQUIRED);
    }

    @Test
    @DisplayName("idempotent re-submit after arrival, and bookings without coordinates, are not blocked")
    void passThroughCases() {
        Booking arrived = booking(BookingStage.ARRIVED, DEST_LAT, DEST_LNG);
        assertThatCode(() -> guard.checkManualArrival(arrived)).doesNotThrowAnyException();

        Booking noCoords = booking(BookingStage.ON_THE_WAY, null, null);
        assertThatCode(() -> guard.checkManualArrival(noCoords)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the switch turns the check off (rollback)")
    void disabled() {
        ArrivalProximityGuard off = new ArrivalProximityGuard(reader, Clock.systemUTC(), false, 200.0, 120);
        Booking b = booking(BookingStage.ON_THE_WAY, DEST_LAT, DEST_LNG);
        when(reader.read(b.getBookingId())).thenReturn(Optional.empty());
        assertThatCode(() -> off.checkManualArrival(b)).doesNotThrowAnyException();
    }
}
