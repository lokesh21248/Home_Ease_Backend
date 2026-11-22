package com.homeease.backend.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import com.homeease.backend.tracking.GpsFixValidator.Raw;
import com.homeease.backend.tracking.GpsFixValidator.Reason;
import com.homeease.backend.tracking.GpsFixValidator.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GpsFixValidatorTest {

    // Hitec City, Hyderabad
    static final double LAT = 17.4435;
    static final double LNG = 78.3772;
    static final long NOW = 1_800_000_000_000L;

    final GpsFixValidator validator = new GpsFixValidator();

    private Raw raw(Double lat, Double lng, Long ts, Double acc, Double speed, Double heading) {
        return new Raw(lat, lng, ts, acc, speed, heading);
    }

    private Raw good(long ts) {
        return raw(LAT, LNG, ts, 8.0, 6.5, 90.0);
    }

    private void assertRejected(Result r, Reason reason) {
        assertThat(r.accepted()).as("accepted").isFalse();
        assertThat(r.reason()).isEqualTo(reason);
        assertThat(r.message()).isNotBlank();
    }

    // ---------------------------------------------------------------- coordinates

    @Test
    @DisplayName("a normal fix is accepted and carried through unchanged")
    void acceptsNormalFix() {
        Result r = validator.validate(good(NOW - 2_000), null, NOW);
        assertThat(r.accepted()).isTrue();
        assertThat(r.fix().lat()).isEqualTo(LAT);
        assertThat(r.fix().lng()).isEqualTo(LNG);
        assertThat(r.fix().accuracyM()).isEqualTo(8.0);
        assertThat(r.fix().speedMps()).isEqualTo(6.5);
        assertThat(r.fix().headingDeg()).isEqualTo(90.0);
    }

    @Test
    @DisplayName("non-finite, out-of-range, missing and 0,0 coordinates are rejected")
    void rejectsBadCoordinates() {
        long ts = NOW - 1_000;
        assertRejected(validator.validate(raw(null, LNG, ts, 5.0, 1.0, 0.0), null, NOW), Reason.INVALID_COORDINATES);
        assertRejected(validator.validate(raw(LAT, null, ts, 5.0, 1.0, 0.0), null, NOW), Reason.INVALID_COORDINATES);
        assertRejected(validator.validate(raw(Double.NaN, LNG, ts, 5.0, 1.0, 0.0), null, NOW), Reason.INVALID_COORDINATES);
        assertRejected(validator.validate(raw(LAT, Double.POSITIVE_INFINITY, ts, 5.0, 1.0, 0.0), null, NOW), Reason.INVALID_COORDINATES);
        assertRejected(validator.validate(raw(90.0001, LNG, ts, 5.0, 1.0, 0.0), null, NOW), Reason.INVALID_COORDINATES);
        assertRejected(validator.validate(raw(-91.0, LNG, ts, 5.0, 1.0, 0.0), null, NOW), Reason.INVALID_COORDINATES);
        assertRejected(validator.validate(raw(LAT, 180.5, ts, 5.0, 1.0, 0.0), null, NOW), Reason.INVALID_COORDINATES);
        assertRejected(validator.validate(raw(0.0, 0.0, ts, 5.0, 1.0, 0.0), null, NOW), Reason.INVALID_COORDINATES);
        assertRejected(validator.validate(null, null, NOW), Reason.INVALID_COORDINATES);
    }

    @Test
    @DisplayName("boundary coordinates (poles, antimeridian) are valid")
    void acceptsBoundaryCoordinates() {
        assertThat(validator.validate(raw(90.0, 180.0, NOW - 1, null, null, null), null, NOW).accepted()).isTrue();
        assertThat(validator.validate(raw(-90.0, -180.0, NOW - 1, null, null, null), null, NOW).accepted()).isTrue();
    }

    // ---------------------------------------------------------------- accuracy / speed / heading

    @Test
    @DisplayName("accuracy: unknown is allowed, 50 m is the limit, worse / negative / NaN are rejected")
    void accuracyRules() {
        long ts = NOW - 1_000;
        assertThat(validator.validate(raw(LAT, LNG, ts, null, null, null), null, NOW).accepted()).isTrue();
        assertThat(validator.validate(raw(LAT, LNG, ts, 50.0, null, null), null, NOW).accepted()).isTrue();
        assertRejected(validator.validate(raw(LAT, LNG, ts, 50.1, null, null), null, NOW), Reason.ACCURACY_TOO_LOW);
        assertRejected(validator.validate(raw(LAT, LNG, ts, -1.0, null, null), null, NOW), Reason.INVALID_ACCURACY);
        assertRejected(validator.validate(raw(LAT, LNG, ts, Double.NaN, null, null), null, NOW), Reason.INVALID_ACCURACY);
    }

    @Test
    @DisplayName("speed: negative means unknown (Android uses -1), NaN and implausible values are rejected")
    void speedRules() {
        long ts = NOW - 1_000;
        Result unknown = validator.validate(raw(LAT, LNG, ts, 5.0, -1.0, null), null, NOW);
        assertThat(unknown.accepted()).isTrue();
        assertThat(unknown.fix().speedMps()).isNull();
        assertThat(validator.validate(raw(LAT, LNG, ts, 5.0, 70.0, null), null, NOW).accepted()).isTrue();
        assertRejected(validator.validate(raw(LAT, LNG, ts, 5.0, 70.1, null), null, NOW), Reason.INVALID_SPEED);
        assertRejected(validator.validate(raw(LAT, LNG, ts, 5.0, Double.NaN, null), null, NOW), Reason.INVALID_SPEED);
    }

    @Test
    @DisplayName("heading is normalised into [0,360) and negative/NaN becomes unknown")
    void headingNormalisation() {
        long ts = NOW - 1_000;
        assertThat(validator.validate(raw(LAT, LNG, ts, 5.0, 1.0, 365.0), null, NOW).fix().headingDeg()).isEqualTo(5.0);
        assertThat(validator.validate(raw(LAT, LNG, ts, 5.0, 1.0, 360.0), null, NOW).fix().headingDeg()).isEqualTo(0.0);
        assertThat(validator.validate(raw(LAT, LNG, ts, 5.0, 1.0, -1.0), null, NOW).fix().headingDeg()).isNull();
        assertThat(validator.validate(raw(LAT, LNG, ts, 5.0, 1.0, Double.NaN), null, NOW).fix().headingDeg()).isNull();
    }

    // ---------------------------------------------------------------- timestamps (fake / replayed)

    @Test
    @DisplayName("fake timestamps: missing, zero, far future and far past are all rejected")
    void fakeTimestamps() {
        assertRejected(validator.validate(raw(LAT, LNG, null, 5.0, 1.0, 0.0), null, NOW), Reason.MISSING_TIMESTAMP);
        assertRejected(validator.validate(raw(LAT, LNG, 0L, 5.0, 1.0, 0.0), null, NOW), Reason.MISSING_TIMESTAMP);
        assertRejected(validator.validate(raw(LAT, LNG, -5L, 5.0, 1.0, 0.0), null, NOW), Reason.MISSING_TIMESTAMP);
        // year 2100 and 1970
        assertRejected(validator.validate(raw(LAT, LNG, 4_102_444_800_000L, 5.0, 1.0, 0.0), null, NOW), Reason.TIMESTAMP_IN_FUTURE);
        assertRejected(validator.validate(raw(LAT, LNG, 86_400_000L, 5.0, 1.0, 0.0), null, NOW), Reason.TIMESTAMP_TOO_OLD);
    }

    @Test
    @DisplayName("future skew up to 5 s is tolerated (clock drift), 5.001 s is not")
    void futureSkewBoundary() {
        assertThat(validator.validate(good(NOW + 5_000), null, NOW).accepted()).isTrue();
        assertRejected(validator.validate(good(NOW + 5_001), null, NOW), Reason.TIMESTAMP_IN_FUTURE);
    }

    @Test
    @DisplayName("age: 120 s is the limit")
    void ageBoundary() {
        assertThat(validator.validate(good(NOW - 120_000), null, NOW).accepted()).isTrue();
        assertRejected(validator.validate(good(NOW - 120_001), null, NOW), Reason.TIMESTAMP_TOO_OLD);
    }

    @Test
    @DisplayName("duplicates, replays and out-of-order fixes are rejected; a newer fix is accepted")
    void ordering() {
        GpsFix previous = validator.validate(good(NOW - 10_000), null, NOW).fix();

        assertRejected(validator.validate(good(NOW - 10_000), previous, NOW), Reason.OUT_OF_ORDER);   // duplicate
        assertRejected(validator.validate(good(NOW - 15_000), previous, NOW), Reason.OUT_OF_ORDER);   // replay of an older one
        assertThat(validator.validate(good(NOW - 7_000), previous, NOW).accepted()).isTrue();
    }

    // ---------------------------------------------------------------- impossible movement

    @Test
    @DisplayName("teleport: ~5 km in 10 s is rejected; the same distance over 10 minutes is fine")
    void teleport() {
        GpsFix previous = validator.validate(raw(LAT, LNG, NOW - 20_000, 5.0, 5.0, 0.0), null, NOW).fix();
        // 0.045 degrees of latitude is about 5 km
        Raw jump = raw(LAT + 0.045, LNG, NOW - 10_000, 5.0, 5.0, 0.0);
        assertRejected(validator.validate(jump, previous, NOW), Reason.IMPOSSIBLE_MOVEMENT);

        GpsFix slowPrev = new GpsFix(LAT, LNG, NOW - 600_000, 5.0, 5.0, 0.0);
        // too old for the age window on its own, so validate at a clock that keeps it fresh
        Raw slow = raw(LAT + 0.045, LNG, NOW - 1_000, 5.0, 5.0, 0.0);
        assertThat(validator.validate(slow, slowPrev, NOW).accepted()).as("5 km in ~10 min is ~8 m/s").isTrue();
    }

    @Test
    @DisplayName("teleport threshold needs both a real distance (>300 m) and an impossible speed")
    void teleportNeedsDistanceAndSpeed() {
        GpsFix previous = new GpsFix(LAT, LNG, NOW - 3_000, 5.0, 5.0, 0.0);
        // ~100 m in 1 s = 100 m/s but only 100 m: GPS noise, not a teleport
        Raw noise = raw(LAT + 0.0009, LNG, NOW - 2_000, 5.0, 5.0, 0.0);
        assertThat(validator.validate(noise, previous, NOW).accepted()).isTrue();
        // ~445 m in 5 s = 89 m/s: impossible
        Raw teleport = raw(LAT + 0.004, LNG, NOW - 2_000 + 3_000, 5.0, 5.0, 0.0);
        assertRejected(validator.validate(teleport, previous, NOW + 3_000), Reason.IMPOSSIBLE_MOVEMENT);
    }
}
