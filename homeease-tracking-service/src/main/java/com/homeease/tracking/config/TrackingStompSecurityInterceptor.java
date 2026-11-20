package com.homeease.tracking.config;

import com.homeease.tracking.security.JwtTokenValidator;
import com.homeease.tracking.security.TrackingAccessGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;

import java.util.Map;
import java.util.UUID;

/**
 * STOMP inbound security for the tracking broker.
 *
 * <ul>
 *   <li><b>CONNECT</b> never fails (workers that fall back to an {@code X-User-Id} session must still be able
 *       to publish GPS). It records <em>how</em> the session was authenticated: only a cryptographically
 *       verified JWT marks the session {@code verified}.</li>
 *   <li><b>SUBSCRIBE</b> to a booking-scoped topic requires a verified session whose user is a participant of
 *       that booking (fail closed). Wildcard / pattern destinations and the never-published
 *       {@code /topic/tracking/update} stream are refused, because a pattern such as {@code /topic/**} would
 *       otherwise receive every booking's worker location.</li>
 * </ul>
 */
public class TrackingStompSecurityInterceptor implements ChannelInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(TrackingStompSecurityInterceptor.class);

    static final String ATTR_VERIFIED = "tracking.jwtVerified";
    static final String ATTR_ROLE = "tracking.role";

    private static final String[] BOOKING_TOPIC_PREFIXES = {
            "/topic/tracking/", "/topic/track/", "/topic/tracking.", "/topic/track.",
            "/topic/booking/", "/topic/booking."
    };

    private final JwtTokenValidator jwtValidator;
    private final TrackingAccessGuard guard;

    public TrackingStompSecurityInterceptor(JwtTokenValidator jwtValidator, TrackingAccessGuard guard) {
        this.jwtValidator = jwtValidator;
        this.guard = guard;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            authenticateConnection(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            authorizeSubscribe(accessor);
        }
        return message;
    }

    private void authenticateConnection(StompHeaderAccessor accessor) {
        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader == null) {
            authHeader = accessor.getFirstNativeHeader("token");
        }

        String principalName = null;
        boolean verified = false;
        String role = null;

        if (authHeader != null && !authHeader.isBlank()) {
            Map<String, Object> claims = jwtValidator.validateAndExtractClaims(authHeader);
            if (claims != null) {
                Object raw = claims.get("userId") != null ? claims.get("userId") : claims.get("sub");
                try {
                    if (raw != null) {
                        principalName = UUID.fromString(raw.toString()).toString();
                        verified = true;
                        role = claims.get("role") == null ? null : claims.get("role").toString();
                    }
                } catch (IllegalArgumentException ignored) {
                    // malformed subject: treated as unauthenticated below
                }
            }
        }

        if (principalName == null) {
            // Compatibility for GPS publishers on header-only sessions. NOT sufficient to subscribe.
            String header = accessor.getFirstNativeHeader("X-User-Id");
            if (header != null && !header.isBlank()) {
                principalName = header.trim();
            }
        }

        if (principalName != null) {
            final String name = principalName;
            accessor.setUser(() -> name);
        }
        Map<String, Object> session = accessor.getSessionAttributes();
        if (session != null) {
            session.put(ATTR_VERIFIED, verified);
            if (role != null) {
                session.put(ATTR_ROLE, role);
            }
        }
        logger.info("STOMP CONNECT principal={} verifiedJwt={}", principalName, verified);
    }

    private void authorizeSubscribe(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null) {
            return;
        }
        if (destination.indexOf('*') >= 0 || destination.indexOf('?') >= 0 || destination.indexOf('{') >= 0) {
            logger.warn("Rejected pattern/wildcard subscription '{}'", destination);
            throw new SecurityException("Wildcard subscriptions are not allowed.");
        }
        for (String prefix : BOOKING_TOPIC_PREFIXES) {
            if (!destination.startsWith(prefix)) {
                continue;
            }
            String bookingId = destination.substring(prefix.length()).trim();
            if (bookingId.equalsIgnoreCase("update")) {
                throw new SecurityException("No such tracking stream.");
            }
            Map<String, Object> session = accessor.getSessionAttributes();
            boolean verified = session != null && Boolean.TRUE.equals(session.get(ATTR_VERIFIED));
            String role = session != null && session.get(ATTR_ROLE) != null ? session.get(ATTR_ROLE).toString() : null;
            String userId = accessor.getUser() != null ? accessor.getUser().getName() : null;
            guard.authorizeSubscription(userId, role, verified, bookingId);
            return;
        }
    }
}
