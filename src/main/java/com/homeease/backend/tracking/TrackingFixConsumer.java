package com.homeease.backend.tracking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.config.RedisWebSocketBridge;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Second subscriber of the channel the tracking service already publishes every ACCEPTED fix to
 * ({@value RedisWebSocketBridge#TRACKING_BROADCAST_CHANNEL}). Adds no Redis commands per fix: subscribers are pushed
 * to. Each fix is re-validated (range, accuracy, timestamp) and then feeds the PostgreSQL history and the arrival
 * geofence. Pub/sub is fire-and-forget, so a missed message only means one fewer breadcrumb or one more fix before an
 * automatic arrival; the partner's manual "Arrived" button remains available.
 */
@Component
public class TrackingFixConsumer {

    private static final Logger log = LoggerFactory.getLogger(TrackingFixConsumer.class);

    private final ObjectMapper objectMapper;
    private final ObjectProvider<RedisMessageListenerContainer> container;
    private final LocationHistoryRecorder history;
    private final ArrivalDetectionService arrivalDetection;
    private final GpsFixValidator validator = new GpsFixValidator();

    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    public TrackingFixConsumer(ObjectMapper objectMapper,
                               ObjectProvider<RedisMessageListenerContainer> container,
                               LocationHistoryRecorder history,
                               ArrivalDetectionService arrivalDetection) {
        this.objectMapper = objectMapper;
        this.container = container;
        this.history = history;
        this.arrivalDetection = arrivalDetection;
    }

    @PostConstruct
    void subscribe() {
        container.ifAvailable(c -> {
            c.addMessageListener((message, pattern) -> handle(new String(message.getBody(), StandardCharsets.UTF_8)),
                    new ChannelTopic(RedisWebSocketBridge.TRACKING_BROADCAST_CHANNEL));
            log.info("Tracking fix consumer subscribed to {}", RedisWebSocketBridge.TRACKING_BROADCAST_CHANNEL);
        });
    }

    /** @return true when the fix was accepted and processed */
    public boolean handle(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode p = root.path("payload");
            UUID bookingId = uuid(root.path("bookingId").asText(""));
            if (bookingId == null || p.isMissingNode()) {
                rejected.incrementAndGet();
                return false;
            }
            String payloadBooking = p.path("bookingId").asText("");
            if (!payloadBooking.isEmpty() && !payloadBooking.equals(bookingId.toString())) {
                rejected.incrementAndGet();
                return false; // envelope/payload mismatch: never attribute a fix to the wrong booking
            }
            if (p.has("trackingActive") && !p.path("trackingActive").asBoolean(true)) {
                return false;
            }
            long now = System.currentTimeMillis();
            GpsFixValidator.Raw raw = new GpsFixValidator.Raw(
                    num(p, "lat", "latitude"), num(p, "lng", "longitude"),
                    p.hasNonNull("timestamp") ? p.path("timestamp").asLong() : null,
                    num(p, "accuracy", null), num(p, "speed", null), num(p, "bearing", "heading"));
            GpsFixValidator.Result r = validator.validate(raw, null, now);
            if (!r.accepted()) {
                rejected.incrementAndGet();
                log.debug("Tracking fix for booking {} rejected: {}", bookingId, r.reason());
                return false;
            }
            long receivedAt = p.path("serverTimestamp").asLong(now);
            UUID workerId = uuid(p.path("workerId").asText(p.path("partnerId").asText("")));

            history.offer(bookingId, workerId, r.fix(), receivedAt);
            arrivalDetection.onFix(bookingId, r.fix());
            accepted.incrementAndGet();
            return true;
        } catch (Exception e) {
            rejected.incrementAndGet();
            log.debug("Unreadable tracking fix message: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    public long acceptedCount() {
        return accepted.get();
    }

    public long rejectedCount() {
        return rejected.get();
    }

    private static UUID uuid(String s) {
        try {
            return s == null || s.isBlank() ? null : UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Double num(JsonNode n, String a, String b) {
        JsonNode v = n.hasNonNull(a) ? n.get(a) : (b == null ? null : n.get(b));
        return v != null && v.isNumber() ? v.asDouble() : null;
    }
}
