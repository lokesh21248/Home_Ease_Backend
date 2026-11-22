package com.homeease.backend.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import com.homeease.backend.tracking.ArrivalGeofence.Evaluation;
import com.homeease.backend.tracking.ArrivalGeofence.Verdict;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ArrivalGeofenceTest {

    // Customer address, Madhapur (Hyderabad). 0.001 degrees of latitude is about 111 m.
    static final double DEST_LAT = 17.4486;
    static final double DEST_LNG = 78.3908;
    static final long T0 = 1_800_000_000_000L;

    final ArrivalGeofence geofence = new ArrivalGeofence();
    final UUID booking = UUID.randomUUID();

    /** A fix {@code northMeters} north of the destination. */
    private GpsFix fix(double northMeters, long ts, Double accuracy, Double speed) {
        return new GpsFix(DEST_LAT + northMeters / 111_195.0, DEST_LNG, ts, accuracy, speed, 0.0);
    }

    private Evaluation eval(UUID id, GpsFix f) {
        return geofence.evaluate(id, f, DEST_LAT, DEST_LNG);
    }

    @Test
    @DisplayName("far away is OUTSIDE, within 500 m is NEARBY, neither confirms an arrival")
    void outsideAndNearby() {
        assertThat(eval(booking, fix(2_400, T0, 6.0, 8.0)).verdict()).isEqualTo(Verdict.OUTSIDE);
        Evaluation nearby = eval(booking, fix(300, T0 + 3_000, 6.0, 5.0));
        assertThat(nearby.verdict()).isEqualTo(Verdict.NEARBY);
        assertThat(nearby.distanceMeters()).isBetween(295.0, 305.0);
    }

    @Test
    @DisplayName("one fix inside the radius is NOT an arrival")
    void singleInsideFixIsNotArrival() {
        Evaluation e = eval(booking, fix(30, T0, 5.0, 0.4));
        assertThat(e.verdict()).isEqualTo(Verdict.INSIDE_CONFIRMING);
        assertThat(e.consecutiveFixes()).isEqualTo(1);
    }

    @Test
    @DisplayName("three consecutive good fixes spanning at least 6 s confirm the arrival")
    void threeConsecutiveFixesConfirm() {
        assertThat(eval(booking, fix(40, T0, 5.0, 0.5)).verdict()).isEqualTo(Verdict.INSIDE_CONFIRMING);
        assertThat(eval(booking, fix(30, T0 + 3_000, 5.0, 0.3)).verdict()).isEqualTo(Verdict.INSIDE_CONFIRMING);
        Evaluation third = eval(booking, fix(25, T0 + 6_000, 5.0, 0.0));
        assertThat(third.verdict()).isEqualTo(Verdict.ARRIVED);
        assertThat(third.consecutiveFixes()).isEqualTo(3);
    }

    @Test
    @DisplayName("three fixes arriving too fast (under 6 s of dwell) do not confirm yet; a later fix does")
    void minimumDwell() {
        eval(booking, fix(40, T0, 5.0, 0.5));
        eval(booking, fix(35, T0 + 1_000, 5.0, 0.5));
        assertThat(eval(booking, fix(30, T0 + 2_000, 5.0, 0.5)).verdict()).isEqualTo(Verdict.INSIDE_CONFIRMING);
        assertThat(eval(booking, fix(30, T0 + 6_500, 5.0, 0.5)).verdict()).isEqualTo(Verdict.ARRIVED);
    }

    @Test
    @DisplayName("a fix exactly at the radius counts; just outside does not")
    void radiusBoundary() {
        assertThat(eval(booking, fix(74, T0, 5.0, 0.0)).verdict()).isEqualTo(Verdict.INSIDE_CONFIRMING);
        UUID other = UUID.randomUUID();
        assertThat(eval(other, fix(80, T0, 5.0, 0.0)).verdict()).isEqualTo(Verdict.NEARBY);
    }

    @Test
    @DisplayName("poor accuracy (>40 m) is ignored: it cannot confirm an arrival and cannot cancel one in progress")
    void poorAccuracyIsIgnored() {
        assertThat(eval(booking, fix(20, T0, 65.0, 0.0)).verdict()).isEqualTo(Verdict.IGNORED);

        eval(booking, fix(40, T0 + 1_000, 5.0, 0.0));
        eval(booking, fix(35, T0 + 4_000, 5.0, 0.0));
        // a drifting indoor fix 300 m away with 90 m accuracy must not reset the streak
        Evaluation drift = eval(booking, fix(300, T0 + 5_000, 90.0, 0.0));
        assertThat(drift.verdict()).isEqualTo(Verdict.IGNORED);
        assertThat(drift.consecutiveFixes()).isEqualTo(2);
        assertThat(eval(booking, fix(30, T0 + 7_500, 5.0, 0.0)).verdict()).isEqualTo(Verdict.ARRIVED);
    }

    @Test
    @DisplayName("unknown accuracy is allowed to count")
    void unknownAccuracyCounts() {
        eval(booking, fix(40, T0, null, null));
        eval(booking, fix(35, T0 + 3_000, null, null));
        assertThat(eval(booking, fix(30, T0 + 6_000, null, null)).verdict()).isEqualTo(Verdict.ARRIVED);
    }

    @Test
    @DisplayName("driving past at road speed never arrives, however many fixes fall inside the radius")
    void drivingPastIsNotArrival() {
        long ts = T0;
        for (int i = 0; i < 6; i++) {
            Evaluation e = eval(booking, fix(60 - i * 20, ts, 6.0, 9.0)); // 9 m/s = 32 km/h
            assertThat(e.verdict()).isNotEqualTo(Verdict.ARRIVED);
            ts += 3_000;
        }
    }

    @Test
    @DisplayName("a good fix outside the radius (or a fast one inside) restarts the count")
    void leavingRestartsTheCount() {
        eval(booking, fix(40, T0, 5.0, 0.5));
        eval(booking, fix(35, T0 + 3_000, 5.0, 0.5));
        assertThat(eval(booking, fix(200, T0 + 5_000, 5.0, 4.0)).verdict()).isEqualTo(Verdict.NEARBY);
        // back inside: needs a full three again
        assertThat(eval(booking, fix(40, T0 + 8_000, 5.0, 0.5)).consecutiveFixes()).isEqualTo(1);
        assertThat(eval(booking, fix(35, T0 + 11_000, 5.0, 0.5)).verdict()).isEqualTo(Verdict.INSIDE_CONFIRMING);
        assertThat(eval(booking, fix(30, T0 + 14_000, 5.0, 0.5)).verdict()).isEqualTo(Verdict.ARRIVED);
    }

    @Test
    @DisplayName("replayed or out-of-order fixes are ignored and cannot inflate the count")
    void replayIgnored() {
        eval(booking, fix(40, T0 + 3_000, 5.0, 0.0));
        assertThat(eval(booking, fix(40, T0 + 3_000, 5.0, 0.0)).verdict()).isEqualTo(Verdict.IGNORED);
        assertThat(eval(booking, fix(40, T0, 5.0, 0.0)).verdict()).isEqualTo(Verdict.IGNORED);
        assertThat(eval(booking, fix(40, T0 + 3_000, 5.0, 0.0)).consecutiveFixes()).isEqualTo(1);
    }

    @Test
    @DisplayName("bookings are independent: one customer's streak never affects another's")
    void bookingsAreIsolated() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        eval(a, fix(40, T0, 5.0, 0.0));
        eval(a, fix(35, T0 + 3_000, 5.0, 0.0));
        assertThat(eval(b, fix(30, T0 + 6_000, 5.0, 0.0)).verdict()).isEqualTo(Verdict.INSIDE_CONFIRMING);
        assertThat(eval(a, fix(30, T0 + 6_000, 5.0, 0.0)).verdict()).isEqualTo(Verdict.ARRIVED);
    }

    @Test
    @DisplayName("reset forgets the streak")
    void resetForgets() {
        eval(booking, fix(40, T0, 5.0, 0.0));
        eval(booking, fix(35, T0 + 3_000, 5.0, 0.0));
        geofence.reset(booking);
        assertThat(eval(booking, fix(30, T0 + 6_000, 5.0, 0.0)).consecutiveFixes()).isEqualTo(1);
    }

    @Test
    @DisplayName("memory is bounded: far more bookings than the cap never grow the map past it")
    void memoryIsBounded() {
        for (int i = 0; i < 6_500; i++) {
            eval(UUID.randomUUID(), fix(40, T0, 5.0, 0.0));
        }
        assertThat(geofence.trackedBookings()).isLessThanOrEqualTo(5_000);
    }
}
