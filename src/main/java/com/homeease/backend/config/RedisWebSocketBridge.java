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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cross-Microservice WebSocket Bridge:
 * Listens to Redis pub/sub channel "ws:broadcast:tracking" published by the
 * homeease-tracking microservice (port 8082) and forwards live coordinate events
 * to mobile clients connected to homeease-core's STOMP broker (port 8080).
 *
 * This completely resolves the split-brain issue between port 8080 and 8082.
 *
 * <p>Direction of flow: tracking service PUBLISHES, this bridge SUBSCRIBES.
 *
 * <p>Integrity guards applied before anything is forwarded to a customer-facing topic:
 * <ul>
 *   <li><b>No cross-booking leakage:</b> the destination must be exactly {@code /topic/tracking/<bookingId>}
 *       for the message's own bookingId, and a payload that names a different bookingId is dropped. A
 *       malformed or forged message can therefore never be delivered to another booking's topic.</li>
 *   <li><b>No duplicates / out-of-order events:</b> per booking, a payload whose {@code timestamp} is not newer
 *       than the last forwarded one is dropped (the tracking service already guarantees strictly increasing
 *       timestamps per booking, so this only catches redeliveries after a Redis reconnect or races between
 *       service instances). Memory is bounded (LRU, {@value #MAX_TRACKED_BOOKINGS} bookings).</li>
 * </ul>
 */
@Configuration
public class RedisWebSocketBridge {

    private static final Logger logger = LoggerFactory.getLogger(RedisWebSocketBridge.class);
    public static final String TRACKING_BROADCAST_CHANNEL = "ws:broadcast:tracking";
    private static final String TRACKING_TOPIC_PREFIX = "/topic/tracking/";
    private static final String TRACK_ALIAS_PREFIX = "/topic/track/";
    static final int MAX_TRACKED_BOOKINGS = 5000;

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    private final Map<String, Long> lastForwardedTimestamp = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > MAX_TRACKED_BOOKINGS;
                }
            });
    private final AtomicLong forwarded = new AtomicLong();
    private final AtomicLong droppedMismatch = new AtomicLong();
    private final AtomicLong droppedStale = new AtomicLong();
    private final AtomicLong droppedMalformed = new AtomicLong();

    public RedisWebSocketBridge(SimpMessagingTemplate messagingTemplate, ObjectMapper objectMapper) {
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        MessageListener listener = (Message message, byte[] pattern) ->
                relay(new String(message.getBody(), StandardCharsets.UTF_8));

        container.addMessageListener(listener, new ChannelTopic(TRACKING_BROADCAST_CHANNEL));
        logger.info("Registered Redis WebSocket bridge listener on channel: {}", TRACKING_BROADCAST_CHANNEL);
        return container;
    }

    /**
     * Validates one pub/sub message and forwards it to the STOMP broker.
     *
     * @return true if the message was forwarded, false if it was dropped (reason is counted, payload never logged)
     */
    boolean relay(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);

            String destination = root.path("destination").asText("");
            String bookingId = root.path("bookingId").asText("");
            JsonNode payloadNode = root.path("payload");

            if (destination.isBlank() || payloadNode.isMissingNode() || payloadNode.isNull()) {
                droppedMalformed.incrementAndGet();
                return false;
            }

            // Cross-booking guard: destination is derived from the booking id, never trusted on its own.
            if (bookingId.isBlank() || !destination.equals(TRACKING_TOPIC_PREFIX + bookingId)) {
                droppedMismatch.incrementAndGet();
                logger.warn("Bridge dropped a message whose destination does not match its bookingId");
                return false;
            }
            String payloadBookingId = payloadNode.path("bookingId").asText("");
            if (!payloadBookingId.isEmpty() && !payloadBookingId.equals(bookingId)) {
                droppedMismatch.incrementAndGet();
                logger.warn("Bridge dropped a message whose payload bookingId differs from its envelope bookingId");
                return false;
            }

            // Duplicate / out-of-order guard (only when the payload carries a timestamp).
            JsonNode ts = payloadNode.path("timestamp");
            if (ts.isNumber()) {
                long newTs = ts.asLong();
                synchronized (lastForwardedTimestamp) {
                    Long last = lastForwardedTimestamp.get(bookingId);
                    if (last != null && newTs <= last) {
                        droppedStale.incrementAndGet();
                        return false;
                    }
                    lastForwardedTimestamp.put(bookingId, newTs);
                }
            }

            // Forward to the exact destination, and to the alternate alias topic for the same booking
            messagingTemplate.convertAndSend(destination, payloadNode);
            messagingTemplate.convertAndSend(TRACK_ALIAS_PREFIX + bookingId, payloadNode);
            forwarded.incrementAndGet();
            logger.debug("Redis WebSocket bridge relayed event for destination: {}", destination);
            return true;
        } catch (Exception e) {
            droppedMalformed.incrementAndGet();
            logger.warn("Failed to process Redis WebSocket bridge message: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    public long forwardedCount() { return forwarded.get(); }

    public long droppedMismatchCount() { return droppedMismatch.get(); }

    public long droppedStaleCount() { return droppedStale.get(); }

    public long droppedMalformedCount() { return droppedMalformed.get(); }
}
