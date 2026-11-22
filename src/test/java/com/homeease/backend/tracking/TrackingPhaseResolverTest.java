package com.homeease.backend.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.tracking.TrackingPhaseResolver.DeviceGpsState;
import com.homeease.backend.tracking.TrackingPhaseResolver.Input;
import com.homeease.backend.tracking.TrackingPhaseResolver.Resolution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TrackingPhaseResolverTest {

    static final long NOW = 1_800_000_000_000L;
    final TrackingPhaseResolver resolver = new TrackingPhaseResolver(); // live window 30 s, nearby radius 500 m

    private Resolution resolve(BookingStage stage, Long lastFixAgoMs, Double distance, DeviceGpsState dev) {
        Long received = lastFixAgoMs == null ? null : NOW - lastFixAgoMs;
        return resolver.resolve(new Input(stage, received, distance, dev, NOW));
    }

    @Test
    @DisplayName("booking stages map to the customer-facing lifecycle")
    void stageMapping() {
        assertThat(resolve(BookingStage.SEARCHING, null, null, null).phase()).isEqualTo(TrackingPhase.BOOKING_CONFIRMED);
        assertThat(resolve(BookingStage.REQUESTED, null, null, null).phase()).isEqualTo(TrackingPhase.BOOKING_CONFIRMED);
        assertThat(resolve(BookingStage.PENDING, null, null, null).phase()).isEqualTo(TrackingPhase.BOOKING_CONFIRMED);
        assertThat(resolve(BookingStage.ACCEPTED, null, null, null).phase()).isEqualTo(TrackingPhase.PARTNER_ASSIGNED);
        assertThat(resolve(BookingStage.CONFIRMED, null, null, null).phase()).isEqualTo(TrackingPhase.PARTNER_ASSIGNED);
        assertThat(resolve(BookingStage.ARRIVED, 1_000L, 10.0, DeviceGpsState.OK).phase()).isEqualTo(TrackingPhase.ARRIVED);
        assertThat(resolve(BookingStage.IN_PROGRESS, null, null, null).phase()).isEqualTo(TrackingPhase.SERVICE_STARTED);
        assertThat(resolve(BookingStage.COMPLETED, null, null, null).phase()).isEqualTo(TrackingPhase.SERVICE_COMPLETED);
        assertThat(resolve(BookingStage.CANCELLED, null, null, null).phase()).isEqualTo(TrackingPhase.CANCELLED);
        assertThat(resolve(BookingStage.EXPIRED, null, null, null).phase()).isEqualTo(TrackingPhase.CANCELLED);
        assertThat(resolve(BookingStage.WORKER_NOT_FOUND, null, null, null).phase()).isEqualTo(TrackingPhase.CANCELLED);
        assertThat(resolve(null, null, null, null).phase()).isEqualTo(TrackingPhase.BOOKING_CONFIRMED);
    }

    @Test
    @DisplayName("travelling with a fresh fix is LIVE; EN_ROUTE far away, NEARBY within 500 m")
    void liveEnRouteAndNearby() {
        Resolution far = resolve(BookingStage.ON_THE_WAY, 4_000L, 3_200.0, DeviceGpsState.OK);
        assertThat(far.phase()).isEqualTo(TrackingPhase.PARTNER_EN_ROUTE);
        assertThat(far.live()).isTrue();
        assertThat(far.lastFixAgeMs()).isEqualTo(4_000L);

        Resolution near = resolve(BookingStage.ON_THE_WAY, 4_000L, 420.0, DeviceGpsState.OK);
        assertThat(near.phase()).isEqualTo(TrackingPhase.PARTNER_NEARBY);
        assertThat(near.live()).isTrue();

        assertThat(resolve(BookingStage.ON_THE_WAY, 1_000L, 500.0, null).phase()).isEqualTo(TrackingPhase.PARTNER_NEARBY);
        assertThat(resolve(BookingStage.ON_THE_WAY, 1_000L, 500.1, null).phase()).isEqualTo(TrackingPhase.PARTNER_EN_ROUTE);
        // distance unknown: still en route, never nearby by guess
        assertThat(resolve(BookingStage.ON_THE_WAY, 1_000L, null, null).phase()).isEqualTo(TrackingPhase.PARTNER_EN_ROUTE);
    }

    @Test
    @DisplayName("NEVER live without a recent valid fix: none ever, or older than 30 s")
    void neverLiveWithoutRecentFix() {
        Resolution none = resolve(BookingStage.ON_THE_WAY, null, null, null);
        assertThat(none.phase()).isEqualTo(TrackingPhase.LOCATION_UNAVAILABLE);
        assertThat(none.live()).isFalse();
        assertThat(none.lastFixAgeMs()).isNull();

        Resolution edge = resolve(BookingStage.ON_THE_WAY, 30_000L, 800.0, DeviceGpsState.OK);
        assertThat(edge.live()).as("exactly 30 s old is still inside the window").isTrue();

        Resolution stale = resolve(BookingStage.ON_THE_WAY, 30_001L, 800.0, DeviceGpsState.OK);
        assertThat(stale.phase()).isEqualTo(TrackingPhase.TRACKING_INTERRUPTED);
        assertThat(stale.live()).isFalse();
    }

    @Test
    @DisplayName("the partner phone's own report explains the outage")
    void deviceStateExplainsOutage() {
        // permission denied wins even over a (stale) fix and over "no fix yet"
        assertThat(resolve(BookingStage.ON_THE_WAY, null, null, DeviceGpsState.PERMISSION_DENIED).phase())
                .isEqualTo(TrackingPhase.GPS_PERMISSION_DENIED);
        assertThat(resolve(BookingStage.ON_THE_WAY, 90_000L, 900.0, DeviceGpsState.PERMISSION_DENIED).phase())
                .isEqualTo(TrackingPhase.GPS_PERMISSION_DENIED);
        assertThat(resolve(BookingStage.ON_THE_WAY, 90_000L, 900.0, DeviceGpsState.PERMISSION_DENIED).live()).isFalse();

        // GPS switched off after earlier updates: unavailable, not merely "interrupted"
        assertThat(resolve(BookingStage.ON_THE_WAY, 90_000L, 900.0, DeviceGpsState.GPS_DISABLED).phase())
                .isEqualTo(TrackingPhase.LOCATION_UNAVAILABLE);
        // a fresh fix beats a stale "GPS disabled" note
        assertThat(resolve(BookingStage.ON_THE_WAY, 2_000L, 900.0, DeviceGpsState.GPS_DISABLED).live()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = BookingStage.class, mode = EnumSource.Mode.EXCLUDE, names = "ON_THE_WAY")
    @DisplayName("only an ON_THE_WAY booking can ever be live, even with a brand-new fix")
    void onlyTravellingCanBeLive(BookingStage stage) {
        assertThat(resolve(stage, 500L, 50.0, DeviceGpsState.OK).live()).as(stage.name()).isFalse();
    }

    @Test
    @DisplayName("a fix 'from the future' (clock skew on the server side) never produces a negative age")
    void ageNeverNegative() {
        Resolution r = resolver.resolve(new Input(BookingStage.ON_THE_WAY, NOW + 5_000, 800.0, DeviceGpsState.OK, NOW));
        assertThat(r.lastFixAgeMs()).isZero();
        assertThat(r.live()).isTrue();
    }
}
