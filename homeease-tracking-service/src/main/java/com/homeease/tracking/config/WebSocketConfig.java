package com.homeease.tracking.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.tracking.security.JwtTokenValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.UUID;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketConfig.class);

    private final JwtTokenValidator jwtValidator;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public WebSocketConfig(JwtTokenValidator jwtValidator,
                           StringRedisTemplate redisTemplate,
                           ObjectMapper objectMapper) {
        this.jwtValidator = jwtValidator;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();

        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor != null) {
                    if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                        authenticateConnection(accessor);
                    } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                        String destination = accessor.getDestination();
                        if (destination != null && (destination.startsWith("/topic/tracking/") || destination.startsWith("/topic/track/"))) {
                            String bookingId = destination.substring(destination.lastIndexOf('/') + 1).trim();
                            authorizeSubscription(accessor, bookingId);
                        }
                    }
                }
                return message;
            }
        });
    }

    private void authenticateConnection(StompHeaderAccessor accessor) {
        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader == null) {
            authHeader = accessor.getFirstNativeHeader("token");
        }
        String userIdHeader = accessor.getFirstNativeHeader("X-User-Id");

        String principalName = null;
        if (authHeader != null && !authHeader.isBlank()) {
            UUID fromJwt = jwtValidator.extractUserId(authHeader);
            if (fromJwt != null) {
                principalName = fromJwt.toString();
            }
        }
        if (principalName == null && userIdHeader != null && !userIdHeader.isBlank()) {
            principalName = userIdHeader.trim();
        }

        if (principalName != null) {
            final String finalName = principalName;
            accessor.setUser(() -> finalName);
            logger.info("STOMP WebSocket client authenticated as: {}", finalName);
        }
    }

    private void authorizeSubscription(StompHeaderAccessor accessor, String bookingId) {
        String assignmentKey = "tracking:assignment:" + bookingId;
        String assignmentJson = null;
        try {
            assignmentJson = redisTemplate.opsForValue().get(assignmentKey);
        } catch (Exception e) {
            logger.warn("Could not query assignment for subscription authorization on booking {}: {}", bookingId, e.getMessage());
        }

        // If no assignment exists in Redis (e.g. simulator testing BK-TEST-1001), allow subscription
        if (assignmentJson == null || assignmentJson.isBlank()) {
            logger.debug("No assignment metadata found for booking {}. Permitting subscription (simulator/unrestricted mode).", bookingId);
            return;
        }

        try {
            JsonNode node = objectMapper.readTree(assignmentJson);
            String customerId = node.path("customerId").asText("");
            String workerId = node.path("workerId").asText("");
            String userId = node.path("userId").asText("");

            String subscriberId = accessor.getUser() != null ? accessor.getUser().getName() : null;
            if (subscriberId == null) {
                String authHeader = accessor.getFirstNativeHeader("Authorization");
                if (authHeader == null) authHeader = accessor.getFirstNativeHeader("token");
                if (authHeader != null && !authHeader.isBlank()) {
                    UUID fromJwt = jwtValidator.extractUserId(authHeader);
                    if (fromJwt != null) subscriberId = fromJwt.toString();
                }
                if (subscriberId == null) {
                    String h = accessor.getFirstNativeHeader("X-User-Id");
                    if (h != null && !h.isBlank()) subscriberId = h.trim();
                }
            }

            if (subscriberId == null) {
                logger.warn("Unauthenticated client attempted subscription to /topic/tracking/{}", bookingId);
                throw new SecurityException("Authentication required to subscribe to tracking for booking " + bookingId);
            }

            boolean isAuthorized = (!customerId.isBlank() && customerId.equalsIgnoreCase(subscriberId)) ||
                    (!workerId.isBlank() && workerId.equalsIgnoreCase(subscriberId)) ||
                    (!userId.isBlank() && userId.equalsIgnoreCase(subscriberId));

            if (!isAuthorized) {
                logger.warn("Security violation: user {} is not authorized to subscribe to booking {}", subscriberId, bookingId);
                throw new SecurityException("User " + subscriberId + " is not authorized to subscribe to booking " + bookingId);
            }

            logger.info("Authorized subscriber {} for tracking topic on booking {}", subscriberId, bookingId);

        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            logger.warn("Error verifying subscription authorization: {}", e.getMessage());
        }
    }
}
