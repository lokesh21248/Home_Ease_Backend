package com.homeease.tracking.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.tracking.exception.AuthenticationRequiredException;
import com.homeease.tracking.security.JwtTokenValidator;
import com.homeease.tracking.security.TestJwt;
import com.homeease.tracking.security.TrackingAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class TrackingStompSecurityInterceptorTest {

    private static final String BOOKING = UUID.randomUUID().toString();
    private static final String OTHER_BOOKING = UUID.randomUUID().toString();
    private static final String CUSTOMER = UUID.randomUUID().toString();
    private static final String WORKER = UUID.randomUUID().toString();
    private static final String STRANGER = UUID.randomUUID().toString();

    private TrackingStompSecurityInterceptor interceptor;

    @BeforeEach
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("tracking:assignment:" + BOOKING)).thenReturn(
                "{\"customerId\":\"" + CUSTOMER + "\",\"userId\":\"" + WORKER + "\",\"workerId\":\"" + WORKER + "\"}");
        when(ops.get("tracking:assignment:" + OTHER_BOOKING)).thenReturn(
                "{\"customerId\":\"" + STRANGER + "\",\"userId\":\"" + UUID.randomUUID() + "\"}");
        ObjectMapper om = new ObjectMapper();
        JwtTokenValidator jwt = new JwtTokenValidator(om, TestJwt.SECRET);
        interceptor = new TrackingStompSecurityInterceptor(jwt, new TrackingAccessGuard(jwt, redis, om, 12, 300));
    }

    private StompHeaderAccessor connect(Map<String, Object> session, String... nativeHeaders) {
        StompHeaderAccessor acc = StompHeaderAccessor.create(StompCommand.CONNECT);
        acc.setSessionAttributes(session);
        for (int i = 0; i < nativeHeaders.length; i += 2) {
            acc.addNativeHeader(nativeHeaders[i], nativeHeaders[i + 1]);
        }
        acc.setLeaveMutable(true);
        Message<byte[]> msg = MessageBuilder.createMessage(new byte[0], acc.getMessageHeaders());
        interceptor.preSend(msg, null);
        return acc;
    }

    private void subscribe(Map<String, Object> session, StompHeaderAccessor connected, String destination) {
        StompHeaderAccessor acc = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        acc.setSessionAttributes(session);
        acc.setDestination(destination);
        acc.setUser(connected.getUser());
        acc.setLeaveMutable(true);
        interceptor.preSend(MessageBuilder.createMessage(new byte[0], acc.getMessageHeaders()), null);
    }

    @Test
    void verifiedCustomerSubscribesToOwnBookingOnEveryTrackingTopicFamily() {
        Map<String, Object> session = new HashMap<>();
        StompHeaderAccessor c = connect(session, "Authorization", TestJwt.bearer(CUSTOMER, "CUSTOMER"));
        assertThat(c.getUser().getName()).isEqualTo(CUSTOMER);

        subscribe(session, c, "/topic/tracking/" + BOOKING);
        subscribe(session, c, "/topic/track/" + BOOKING);
        subscribe(session, c, "/topic/booking/" + BOOKING);
    }

    @Test
    void customerCannotSubscribeToAnotherBookingsWorkerLocation() {
        Map<String, Object> session = new HashMap<>();
        StompHeaderAccessor c = connect(session, "Authorization", TestJwt.bearer(CUSTOMER, "CUSTOMER"));

        assertThatThrownBy(() -> subscribe(session, c, "/topic/tracking/" + OTHER_BOOKING))
                .isInstanceOf(SecurityException.class).isNotInstanceOf(AuthenticationRequiredException.class);
        assertThatThrownBy(() -> subscribe(session, c, "/topic/track/" + OTHER_BOOKING))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void headerOnlySessionStillConnectsForGpsPublishingButCannotSubscribe() {
        Map<String, Object> session = new HashMap<>();
        // Worker app fallback: X-User-Id with no signed token. CONNECT must succeed (ingest compatibility)...
        StompHeaderAccessor c = connect(session, "X-User-Id", CUSTOMER);
        assertThat(c.getUser().getName()).isEqualTo(CUSTOMER);
        assertThat(session.get(TrackingStompSecurityInterceptor.ATTR_VERIFIED)).isEqualTo(false);

        // ...but a participant's bare UUID must never be enough to read the location stream.
        assertThatThrownBy(() -> subscribe(session, c, "/topic/tracking/" + BOOKING))
                .isInstanceOf(AuthenticationRequiredException.class);
    }

    @Test
    void forgedTokenIsTreatedAsUnverified() {
        Map<String, Object> session = new HashMap<>();
        String forged = "Bearer " + TestJwt.token(CUSTOMER, "ADMIN", System.currentTimeMillis() / 1000 + 600,
                "attacker-secret-0123456789-abcdefghijklmnopqrstuvwxyz");
        StompHeaderAccessor c = connect(session, "Authorization", forged);

        assertThat(c.getUser()).isNull();
        assertThatThrownBy(() -> subscribe(session, c, "/topic/tracking/" + BOOKING))
                .isInstanceOf(AuthenticationRequiredException.class);
    }

    @Test
    void wildcardAndPatternSubscriptionsAreRefusedEvenForAVerifiedAdmin() {
        Map<String, Object> session = new HashMap<>();
        StompHeaderAccessor c = connect(session, "Authorization", TestJwt.bearer(STRANGER, "ADMIN"));

        for (String dest : new String[]{"/topic/**", "/topic/tracking/**", "/topic/tracking/*", "/topic/*/" + BOOKING,
                "/topic/tracking/{id}", "/topic/track?"}) {
            assertThatThrownBy(() -> subscribe(session, c, dest))
                    .as("destination %s", dest).isInstanceOf(SecurityException.class);
        }
    }

    @Test
    void theNeverPublishedBroadcastStreamAndNonUuidIdsAreRefused() {
        Map<String, Object> session = new HashMap<>();
        StompHeaderAccessor c = connect(session, "Authorization", TestJwt.bearer(CUSTOMER, "CUSTOMER"));

        assertThatThrownBy(() -> subscribe(session, c, "/topic/tracking/update")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> subscribe(session, c, "/topic/tracking/BK-TEST-1001")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unrelatedTopicsAreNotAffected() {
        Map<String, Object> session = new HashMap<>();
        StompHeaderAccessor c = connect(session, "X-User-Id", WORKER);

        subscribe(session, c, "/topic/worker/" + WORKER + "/requests"); // worker job offers keep working
    }
}
