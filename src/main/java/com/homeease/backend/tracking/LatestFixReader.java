package com.homeease.backend.tracking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.routing.GeoMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads the partner's latest accepted GPS fix for a booking from Redis ({@code tracking:booking:{id}}, written by
 * the tracking service and by the core's ingest path). Read-only; one GET per call.
 */
@Component
public class LatestFixReader {

    public static final String LOCATION_KEY_PREFIX = "tracking:booking:";
    private static final Logger log = LoggerFactory.getLogger(LatestFixReader.class);

    /**
     * @param receivedAtMs server receipt time when present (immune to the partner phone's clock), else device time
     */
    public record LatestFix(double lat, double lng, long receivedAtMs, long deviceTimestampMs,
                            Double accuracyM, Double speedMps, Double headingDeg, boolean trackingActive) {
    }

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public LatestFixReader(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<LatestFix> read(UUID bookingId) {
        String json;
        try {
            json = redisTemplate.opsForValue().get(LOCATION_KEY_PREFIX + bookingId);
        } catch (RuntimeException e) {
            log.warn("Redis read of latest fix failed for booking {}: {}", bookingId, e.getClass().getSimpleName());
            return Optional.empty();
        }
        return parse(json);
    }

    Optional<LatestFix> parse(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode n = objectMapper.readTree(json);
            Double lat = num(n, "lat", "latitude");
            Double lng = num(n, "lng", "longitude");
            if (!GeoMath.isUsableCoordinate(lat, lng)) {
                return Optional.empty();
            }
            long device = n.path("timestamp").asLong(0L);
            long server = n.path("serverTimestamp").asLong(0L);
            long received = server > 0 ? server : device;
            if (received <= 0) {
                return Optional.empty(); // no trustworthy time: cannot be called fresh
            }
            boolean active = !n.has("trackingActive") || n.path("trackingActive").asBoolean(true);
            return Optional.of(new LatestFix(lat, lng, received, device,
                    num(n, "accuracy", "accuracyMeters"), num(n, "speed", "speedMetersPerSecond"),
                    num(n, "bearing", "heading"), active));
        } catch (Exception e) {
            log.warn("Unreadable latest-fix payload: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static Double num(JsonNode n, String a, String b) {
        JsonNode v = n.hasNonNull(a) ? n.get(a) : n.get(b);
        return v != null && v.isNumber() ? v.asDouble() : null;
    }
}
