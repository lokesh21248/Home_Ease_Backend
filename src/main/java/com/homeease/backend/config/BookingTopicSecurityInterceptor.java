package com.homeease.backend.config;

import com.homeease.backend.routing.BookingRouteContextLoader;
import com.homeease.backend.routing.RouteContext;
import com.homeease.backend.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Subscription security for the core STOMP broker's <em>booking-scoped</em> topics:
 * {@code /topic/tracking/{bookingId}}, {@code /topic/track/{bookingId}}, {@code /topic/booking/{bookingId}}
 * and {@code /topic/customer/{userId}/booking}.
 *
 * <p>Before this guard the core broker had no interceptor, so any connected client could subscribe to any
 * booking's live worker location. Now a subscriber must hold a session authenticated by a cryptographically
 * verified JWT <em>and</em> be that booking's customer, its assigned worker, or an admin.</p>
 *
 * <p>Deliberately narrow: CONNECT is never rejected and non-booking destinations (for example the worker app's
 * {@code /topic/worker/{id}/requests}) are untouched, so existing flows keep working. Wildcard subscriptions
 * that could match a protected family are refused.</p>
 */
public class BookingTopicSecurityInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(BookingTopicSecurityInterceptor.class);

    static final String ATTR_USER = "booking.verifiedUserId";
    static final String ATTR_ADMIN = "booking.verifiedAdmin";

    private static final String[] BOOKING_FAMILIES = {"/topic/tracking/", "/topic/track/", "/topic/booking/"};
    private static final String CUSTOMER_FAMILY = "/topic/customer/";

    private final ObjectProvider<JwtService> jwtService;
    private final ObjectProvider<BookingRouteContextLoader> contextLoader;

    public BookingTopicSecurityInterceptor(ObjectProvider<JwtService> jwtService,
                                           ObjectProvider<BookingRouteContextLoader> contextLoader) {
        this.jwtService = jwtService;
        this.contextLoader = contextLoader;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            recordVerifiedIdentity(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            authorize(accessor);
        }
        return message;
    }

    private void recordVerifiedIdentity(StompHeaderAccessor accessor) {
        Map<String, Object> session = accessor.getSessionAttributes();
        if (session == null) {
            return;
        }
        String auth = accessor.getFirstNativeHeader("Authorization");
        if (auth == null) {
            auth = accessor.getFirstNativeHeader("token");
        }
        if (auth == null || auth.isBlank()) {
            return;
        }
        Map<String, Object> claims = jwtService.getObject().validateAndExtractClaims(auth);
        if (claims == null) {
            return;
        }
        Object raw = claims.get("userId") != null ? claims.get("userId") : claims.get("sub");
        try {
            if (raw != null) {
                session.put(ATTR_USER, UUID.fromString(raw.toString()));
                session.put(ATTR_ADMIN, "ADMIN".equalsIgnoreCase(String.valueOf(claims.get("role"))));
            }
        } catch (IllegalArgumentException ignored) {
            // malformed subject -> stays unverified
        }
    }

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private void authorize(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null) {
            return;
        }
        if (couldMatchProtectedFamily(destination)) {
            log.warn("Rejected wildcard subscription '{}' that could expose booking-scoped topics", destination);
            throw new SecurityException("Wildcard subscriptions to booking topics are not allowed.");
        }

        for (String family : BOOKING_FAMILIES) {
            if (destination.startsWith(family)) {
                String sub = destination.substring(family.length()).trim();
                Matcher m = UUID_PATTERN.matcher(sub);
                String bookingIdRaw = m.find() ? m.group() : sub;
                authorizeBookingTopic(accessor, bookingIdRaw);
                return;
            }
        }
        if (destination.startsWith(CUSTOMER_FAMILY)) {
            String rest = destination.substring(CUSTOMER_FAMILY.length());
            int slash = rest.indexOf('/');
            String userSegment = slash < 0 ? rest : rest.substring(0, slash);
            authorizeCustomerTopic(accessor, userSegment);
        }
    }

    /** True when {@code destination} is a pattern whose literal prefix overlaps a protected topic family. */
    static boolean couldMatchProtectedFamily(String destination) {
        int idx = firstWildcard(destination);
        if (idx < 0) {
            return false;
        }
        String literal = destination.substring(0, idx);
        for (String family : BOOKING_FAMILIES) {
            if (family.startsWith(literal) || literal.startsWith(family)) {
                return true;
            }
        }
        return CUSTOMER_FAMILY.startsWith(literal) || literal.startsWith(CUSTOMER_FAMILY);
    }

    private static int firstWildcard(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '*' || c == '?' || c == '{') {
                return i;
            }
        }
        return -1;
    }

    private void authorizeBookingTopic(StompHeaderAccessor accessor, String bookingIdRaw) {
        UUID user = verifiedUser(accessor);
        if (user == null) {
            throw new SecurityException("A verified sign-in is required to subscribe to booking updates.");
        }
        UUID bookingId;
        try {
            bookingId = UUID.fromString(bookingIdRaw);
        } catch (IllegalArgumentException e) {
            throw new SecurityException("Unknown booking topic.");
        }
        if (Boolean.TRUE.equals(accessor.getSessionAttributes().get(ATTR_ADMIN))) {
            return;
        }
        Optional<RouteContext> ctx = contextLoader.getObject().load(bookingId);
        boolean participant = ctx.isPresent()
                && (user.equals(ctx.get().customerUserId())
                || user.equals(ctx.get().workerUserId())
                || user.equals(ctx.get().workerEntityId()));
        if (!participant) {
            log.warn("Denied booking topic subscription: user {} is not a participant of {}", user, bookingId);
            throw new SecurityException("You are not authorized to subscribe to this booking.");
        }
    }

    private void authorizeCustomerTopic(StompHeaderAccessor accessor, String userSegment) {
        UUID user = verifiedUser(accessor);
        if (user == null) {
            throw new SecurityException("A verified sign-in is required to subscribe to booking updates.");
        }
        if (Boolean.TRUE.equals(accessor.getSessionAttributes().get(ATTR_ADMIN))) {
            return;
        }
        if (!user.toString().equalsIgnoreCase(userSegment)) {
            throw new SecurityException("You may only subscribe to your own booking updates.");
        }
    }

    private static UUID verifiedUser(StompHeaderAccessor accessor) {
        Map<String, Object> session = accessor.getSessionAttributes();
        Object v = session == null ? null : session.get(ATTR_USER);
        return v instanceof UUID u ? u : null;
    }
}
