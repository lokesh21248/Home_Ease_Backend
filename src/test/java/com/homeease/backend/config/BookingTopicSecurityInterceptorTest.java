package com.homeease.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.model.entity.User;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.UserRole;
import com.homeease.backend.routing.BookingRouteContextLoader;
import com.homeease.backend.routing.RouteContext;
import com.homeease.backend.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class BookingTopicSecurityInterceptorTest {

    private static final UUID BOOKING = UUID.randomUUID();
    private static final UUID OTHER_BOOKING = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID WORKER_USER = UUID.randomUUID();
    private static final UUID WORKER_ENTITY = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();

    private JwtService jwt;
    private BookingTopicSecurityInterceptor interceptor;

    @BeforeEach
    void setUp() {
        jwt = new JwtService(new ObjectMapper(), "core-interceptor-test-secret-0123456789-abcdefghijkl");
        BookingRouteContextLoader loader = mock(BookingRouteContextLoader.class);
        when(loader.load(BOOKING)).thenReturn(Optional.of(new RouteContext(BOOKING, BookingStage.ON_THE_WAY,
                CUSTOMER, WORKER_USER, WORKER_ENTITY, 17.5, 78.5)));
        when(loader.load(OTHER_BOOKING)).thenReturn(Optional.of(new RouteContext(OTHER_BOOKING, BookingStage.ON_THE_WAY,
                STRANGER, UUID.randomUUID(), UUID.randomUUID(), 17.5, 78.5)));
        ObjectProvider<JwtService> jwtProvider = mock(ObjectProvider.class);
        ObjectProvider<BookingRouteContextLoader> loaderProvider = mock(ObjectProvider.class);
        when(jwtProvider.getObject()).thenReturn(jwt);
        when(loaderProvider.getObject()).thenReturn(loader);
        interceptor = new BookingTopicSecurityInterceptor(jwtProvider, loaderProvider);
    }

    private String bearer(UUID id, UserRole role) {
        return "Bearer " + jwt.generateToken(User.builder().userId(id).role(role).fullName("t").phoneNumber("1").build());
    }

    private Map<String, Object> connect(String... nativeHeaders) {
        Map<String, Object> session = new HashMap<>();
        StompHeaderAccessor acc = StompHeaderAccessor.create(StompCommand.CONNECT);
        acc.setSessionAttributes(session);
        for (int i = 0; i < nativeHeaders.length; i += 2) {
            acc.addNativeHeader(nativeHeaders[i], nativeHeaders[i + 1]);
        }
        acc.setLeaveMutable(true);
        Message<byte[]> m = MessageBuilder.createMessage(new byte[0], acc.getMessageHeaders());
        assertThat(interceptor.preSend(m, null)).as("CONNECT is never rejected").isNotNull();
        return session;
    }

    private void subscribe(Map<String, Object> session, String destination) {
        StompHeaderAccessor acc = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        acc.setSessionAttributes(session);
        acc.setDestination(destination);
        acc.setLeaveMutable(true);
        interceptor.preSend(MessageBuilder.createMessage(new byte[0], acc.getMessageHeaders()), null);
    }

    @Test
    void participantsSubscribeToTheirOwnBooking() {
        for (String dest : new String[]{"/topic/tracking/" + BOOKING, "/topic/track/" + BOOKING, "/topic/booking/" + BOOKING}) {
            subscribe(connect("Authorization", bearer(CUSTOMER, UserRole.CUSTOMER)), dest);
            subscribe(connect("Authorization", bearer(WORKER_USER, UserRole.WORKER)), dest);
            subscribe(connect("Authorization", bearer(WORKER_ENTITY, UserRole.WORKER)), dest);
            subscribe(connect("Authorization", bearer(STRANGER, UserRole.ADMIN)), dest);
        }
    }

    @Test
    void aStrangerCannotListenToSomeoneElsesBooking() {
        Map<String, Object> s = connect("Authorization", bearer(STRANGER, UserRole.CUSTOMER));
        for (String dest : new String[]{"/topic/tracking/" + BOOKING, "/topic/track/" + BOOKING, "/topic/booking/" + BOOKING}) {
            assertThatThrownBy(() -> subscribe(s, dest)).isInstanceOf(SecurityException.class);
        }
    }

    @Test
    void unknownBookingIsDeniedAndMalformedIdIsDenied() {
        Map<String, Object> s = connect("Authorization", bearer(CUSTOMER, UserRole.CUSTOMER));
        assertThatThrownBy(() -> subscribe(s, "/topic/tracking/" + UUID.randomUUID())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> subscribe(s, "/topic/tracking/not-a-uuid")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> subscribe(s, "/topic/tracking/update")).isInstanceOf(SecurityException.class);
    }

    @Test
    void uuidOnlyHeaderSessionsAndForgedTokensCannotSubscribe() {
        Map<String, Object> headerOnly = connect("X-User-Id", CUSTOMER.toString());
        assertThatThrownBy(() -> subscribe(headerOnly, "/topic/tracking/" + BOOKING)).isInstanceOf(SecurityException.class);

        Map<String, Object> bareUuidBearer = connect("Authorization", "Bearer " + CUSTOMER);
        assertThatThrownBy(() -> subscribe(bareUuidBearer, "/topic/booking/" + BOOKING)).isInstanceOf(SecurityException.class);

        JwtService attacker = new JwtService(new ObjectMapper(), "attacker-secret-0123456789-abcdefghijklmnopqrstuvwxyz");
        String forged = "Bearer " + attacker.generateToken(User.builder().userId(CUSTOMER).role(UserRole.ADMIN)
                .fullName("x").phoneNumber("1").build());
        Map<String, Object> forgedSession = connect("Authorization", forged);
        assertThatThrownBy(() -> subscribe(forgedSession, "/topic/tracking/" + BOOKING)).isInstanceOf(SecurityException.class);
    }

    @Test
    void customerPrivateTopicIsOwnerOnly() {
        subscribe(connect("Authorization", bearer(CUSTOMER, UserRole.CUSTOMER)), "/topic/customer/" + CUSTOMER + "/booking");
        assertThatThrownBy(() -> subscribe(connect("Authorization", bearer(STRANGER, UserRole.CUSTOMER)),
                "/topic/customer/" + CUSTOMER + "/booking")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> subscribe(connect("X-User-Id", CUSTOMER.toString()),
                "/topic/customer/" + CUSTOMER + "/booking")).isInstanceOf(SecurityException.class);
    }

    @Test
    void wildcardsThatCouldMatchBookingTopicsAreRefused() {
        Map<String, Object> s = connect("Authorization", bearer(STRANGER, UserRole.ADMIN));
        for (String dest : new String[]{"/topic/**", "/topic/*", "/topic/tracking/**", "/topic/tracking/*",
                "/topic/booking/{id}", "/topic/*/" + BOOKING, "/topic/customer/**", "/topic/track/?"}) {
            assertThatThrownBy(() -> subscribe(s, dest)).as(dest).isInstanceOf(SecurityException.class);
        }
    }

    @Test
    void workerOfferTopicsAndOtherDestinationsAreUntouched() {
        // The worker app subscribes on a header-only session; this must keep working.
        Map<String, Object> s = connect("X-User-Id", WORKER_USER.toString());
        subscribe(s, "/topic/worker/" + WORKER_USER + "/requests");
        subscribe(s, "/topic/worker/" + WORKER_USER + "/stream");
        subscribe(s, "/topic/worker/*/requests"); // pattern that cannot reach a booking family
        subscribe(s, "/topic/admin/dashboard");
        assertThat(BookingTopicSecurityInterceptor.couldMatchProtectedFamily("/topic/worker/*/requests")).isFalse();
        assertThat(BookingTopicSecurityInterceptor.couldMatchProtectedFamily("/topic/**")).isTrue();
        assertThat(BookingTopicSecurityInterceptor.couldMatchProtectedFamily("/topic/tracking/" + BOOKING)).isFalse();
    }
}
