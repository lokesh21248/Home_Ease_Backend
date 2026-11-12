package com.homeease.backend.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.charset.StandardCharsets;

/**
 * Cross-Microservice WebSocket Bridge:
 * Listens to Redis pub/sub channel "ws:broadcast:tracking" published by the
 * homeease-tracking microservice (port 8082) and forwards live coordinate events
 * to mobile clients connected to homeease-core's STOMP broker (port 8080).
 *
 * This completely resolves the split-brain issue between port 8080 and 8082.
 */
@Configuration
public class RedisWebSocketBridge {

    private static final Logger logger = LoggerFactory.getLogger(RedisWebSocketBridge.class);
    public static final String TRACKING_BROADCAST_CHANNEL = "ws:broadcast:tracking";

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    public RedisWebSocketBridge(SimpMessagingTemplate messagingTemplate, ObjectMapper objectMapper) {
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        MessageListener listener = (Message message, byte[] pattern) -> {
            try {
                String body = new String(message.getBody(), StandardCharsets.UTF_8);
                JsonNode root = objectMapper.readTree(body);

                String destination = root.path("destination").asText("");
                String bookingId = root.path("bookingId").asText("");
                JsonNode payloadNode = root.path("payload");

                if (!destination.isBlank() && !payloadNode.isMissingNode()) {
                    // Forward to exact destination (e.g., /topic/tracking/{bookingId})
                    messagingTemplate.convertAndSend(destination, payloadNode);

                    // Also forward to alternate tracking alias destination
                    if (!bookingId.isBlank() && destination.startsWith("/topic/tracking/")) {
                        messagingTemplate.convertAndSend("/topic/track/" + bookingId, payloadNode);
                    }

                    logger.debug("Redis WebSocket bridge relayed event for destination: {}", destination);
                }
            } catch (Exception e) {
                logger.warn("Failed to process Redis WebSocket bridge message: {}", e.getMessage());
            }
        };

        container.addMessageListener(listener, new ChannelTopic(TRACKING_BROADCAST_CHANNEL));
        logger.info("Registered Redis WebSocket bridge listener on channel: {}", TRACKING_BROADCAST_CHANNEL);
        return container;
    }
}

