package com.homeease.tracking.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.tracking.exception.AuthenticationRequiredException;
import com.homeease.tracking.exception.RateLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class TrackingAccessGuardTest {

    private static final String BOOKING = UUID.randomUUID().toString();
    private static final String CUSTOMER = UUID.randomUUID().toString();
    private static final String WORKER_USER = UUID.randomUUID().toString();
    private static final String WORKER_ENTITY = UUID.randomUUID().toString();
    private static final String STRANGER = UUID.randomUUID().toString();

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private Clock clock;
    private long nowMs = 1_800_000_000_000L;
    private TrackingAccessGuard guard;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId z) { return this; }
            @Override public long millis() { return nowMs; }
            @Override public Instant instant() { return Instant.ofEpochMilli(nowMs); }
        };
        ObjectMapper om = new ObjectMapper();
        guard = new TrackingAccessGuard(new JwtTokenValidator(om, TestJwt.SECRET), redis, om, clock, 3, 5);
        givenAssignment("{\"bookingId\":\"" + BOOKING + "\",\"customerId\":\"" + CUSTOMER + "\",\"userId\":\""
                + WORKER_USER + "\",\"workerId\":\"" + WORKER_ENTITY + "\",\"status\":\"ACCEPTED\"}");
    }

    private void givenAssignment(String json) {
        when(ops.get("tracking:assignment:" + BOOKING)).thenReturn(json);
    }

    // ───────────────────────── participants ─────────────────────────

    @Test
    void customerAndAssignedWorkerAreParticipants() {
        assertThat(guard.requireParticipant(TestJwt.bearer(CUSTOMER, "CUSTOMER"), BOOKING).userId()).isEqualTo(CUSTOMER);
        assertThat(guard.requireParticipant(TestJwt.bearer(WORKER_USER, "WORKER"), BOOKING).userId()).isEqualTo(WORKER_USER);
        assertThat(guard.requireParticipant(TestJwt.bearer(WORKER_ENTITY, "WORKER"), BOOKING).userId()).isEqualTo(WORKER_ENTITY);
    }

    @Test
    void strangerIsForbiddenNotUnauthenticated() {
        assertThatThrownBy(() -> guard.requireParticipant(TestJwt.bearer(STRANGER, "CUSTOMER"), BOOKING))
                .isInstanceOf(SecurityException.class)
                .isNotInstanceOf(AuthenticationRequiredException.class);
    }

    @Test
    void adminMayReadWithoutBeingAParticipant() {
        assertThat(guard.requireParticipant(TestJwt.bearer(STRANGER, "ADMIN"), BOOKING).isAdmin()).isTrue();
    }

    @Test
    void missingAssignmentFailsClosedInsteadOfPermittingEveryone() {
        givenAssignment(null);
        assertThatThrownBy(() -> guard.requireParticipant(TestJwt.bearer(CUSTOMER, "CUSTOMER"), BOOKING))
                .isInstanceOf(SecurityException.class);
        givenAssignment("{\"status\":\"COMPLETED\",\"trackingActive\":false}"); // no participant ids
        assertThatThrownBy(() -> guard.requireParticipant(TestJwt.bearer(CUSTOMER, "CUSTOMER"), BOOKING))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void redisFailureOrCorruptRecordFailsClosed() {
        when(ops.get("tracking:assignment:" + BOOKING)).thenThrow(new IllegalStateException("redis down"));
        assertThatThrownBy(() -> guard.requireParticipant(TestJwt.bearer(CUSTOMER, "CUSTOMER"), BOOKING))
                .isInstanceOf(SecurityException.class);
        // doReturn(): re-stubbing with when(mock.call()) would invoke the previous (throwing) stub.
        org.mockito.Mockito.doReturn("{not json").when(ops).get("tracking:assignment:" + BOOKING);
        assertThatThrownBy(() -> guard.requireParticipant(TestJwt.bearer(CUSTOMER, "CUSTOMER"), BOOKING))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void nonUuidBookingIdAndWildcardsAreRejected() {
        for (String bad : new String[]{"BK-100", "**", "*", "update", "../x", ""}) {
            assertThatThrownBy(() -> guard.requireParticipant(TestJwt.bearer(CUSTOMER, "CUSTOMER"), bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ───────────────────────── identity must be a verified JWT ─────────────────────────

    @Test
    void onlyAValidSignedUnexpiredTokenAuthenticates() {
        assertThatThrownBy(() -> guard.requireVerifiedJwt(null)).isInstanceOf(AuthenticationRequiredException.class);
        assertThatThrownBy(() -> guard.requireVerifiedJwt("")).isInstanceOf(AuthenticationRequiredException.class);
        assertThatThrownBy(() -> guard.requireVerifiedJwt("Bearer " + CUSTOMER)).isInstanceOf(AuthenticationRequiredException.class);
        assertThatThrownBy(() -> guard.requireVerifiedJwt("Bearer garbage.garbage.garbage")).isInstanceOf(AuthenticationRequiredException.class);

        String wrongSecret = "Bearer " + TestJwt.token(CUSTOMER, "ADMIN", Instant.now().getEpochSecond() + 60,
                "another-secret-0123456789-abcdefghijklmnopqrstuvwxyz");
        assertThatThrownBy(() -> guard.requireVerifiedJwt(wrongSecret)).isInstanceOf(AuthenticationRequiredException.class);

        String expired = "Bearer " + TestJwt.token(CUSTOMER, "CUSTOMER", Instant.now().getEpochSecond() - 10, TestJwt.SECRET);
        assertThatThrownBy(() -> guard.requireVerifiedJwt(expired)).isInstanceOf(AuthenticationRequiredException.class);
    }

    @Test
    void adminGateRequiresTheAdminRoleClaim() {
        assertThat(guard.requireAdmin(TestJwt.bearer(STRANGER, "ADMIN")).isAdmin()).isTrue();
        assertThatThrownBy(() -> guard.requireAdmin(TestJwt.bearer(STRANGER, "CUSTOMER")))
                .isInstanceOf(SecurityException.class).isNotInstanceOf(AuthenticationRequiredException.class);
        assertThatThrownBy(() -> guard.requireAdmin(null)).isInstanceOf(AuthenticationRequiredException.class);
    }

    // ───────────────────────── STOMP subscription ─────────────────────────

    @Test
    void subscriptionNeedsAVerifiedSessionThatIsAParticipant() {
        guard.authorizeSubscription(CUSTOMER, "CUSTOMER", true, BOOKING);

        // Header-only (X-User-Id) session: even a participant's UUID is not enough.
        assertThatThrownBy(() -> guard.authorizeSubscription(CUSTOMER, null, false, BOOKING))
                .isInstanceOf(AuthenticationRequiredException.class);
        assertThatThrownBy(() -> guard.authorizeSubscription(null, null, true, BOOKING))
                .isInstanceOf(AuthenticationRequiredException.class);
        assertThatThrownBy(() -> guard.authorizeSubscription(STRANGER, "CUSTOMER", true, BOOKING))
                .isInstanceOf(SecurityException.class).isNotInstanceOf(AuthenticationRequiredException.class);
    }

    // ───────────────────────── coordinate-route proxy ─────────────────────────

    @Test
    void routeProxyAcceptsJwtOrUuidIdentityButNeverAnonymous() {
        assertThat(guard.requireRouteIdentity(TestJwt.bearer(CUSTOMER, "WORKER"), null)).isEqualTo(CUSTOMER);
        assertThat(guard.requireRouteIdentity(null, WORKER_USER)).isEqualTo(WORKER_USER);          // worker UUID-session compat
        assertThat(guard.requireRouteIdentity("Bearer " + WORKER_USER, null)).isEqualTo(WORKER_USER);

        assertThatThrownBy(() -> guard.requireRouteIdentity(null, null)).isInstanceOf(AuthenticationRequiredException.class);
        assertThatThrownBy(() -> guard.requireRouteIdentity("Bearer nonsense", "not-a-uuid"))
                .isInstanceOf(AuthenticationRequiredException.class);
    }

    @Test
    void routeProxyIsCappedPerIdentityAndRecoversAfterTheWindow() {
        guard.checkRoutePermit("a");
        guard.checkRoutePermit("a");
        guard.checkRoutePermit("a");
        assertThatThrownBy(() -> guard.checkRoutePermit("a")).isInstanceOf(RateLimitExceededException.class);

        guard.checkRoutePermit("b"); // another identity is unaffected

        nowMs += 61_000;
        guard.checkRoutePermit("a");
    }

    @Test
    void routeProxyHasAGlobalSpendCeiling() {
        guard.checkRoutePermit("u1");
        guard.checkRoutePermit("u1");
        guard.checkRoutePermit("u2");
        guard.checkRoutePermit("u2");
        guard.checkRoutePermit("u3");
        // 5 allowed globally per minute: a sixth distinct identity is still refused.
        assertThatThrownBy(() -> guard.checkRoutePermit("u4")).isInstanceOf(RateLimitExceededException.class);
    }
}
