package com.homeease.tracking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.tracking.dto.LocationPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Service
public class LiveTrackingService {

    private static final Logger logger = LoggerFactory.getLogger(LiveTrackingService.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;

    // Cache TTL: 2 hours for in-transit tracking data
    private static final Duration TRACKING_TTL = Duration.ofHours(2);

    public LiveTrackingService(StringRedisTemplate redisTemplate, 
                               ObjectMapper objectMapper, 
                               SimpMessagingTemplate messagingTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Records partner live coordinates in Redis (Key-Value + Redis GEO)
     * and broadcasts live to customer WebSocket subscribers.
     */
    public LocationPayload processLocationUpdate(String bookingId, LocationPayload payload) {
        if (payload == null || payload.getLat() == null || payload.getLng() == null) {
            return payload;
        }

        if (payload.getBookingId() == null || payload.getBookingId().isBlank()) {
            payload.setBookingId(bookingId);
        }

        if (payload.getTimestamp() == null) {
            payload.setTimestamp(System.currentTimeMillis());
        }

        try {
            // 1. Serialize full location details into Redis Key with 2-hour TTL
            String json = objectMapper.writeValueAsString(payload);
            String trackingKey = "tracking:booking:" + bookingId;
            redisTemplate.opsForValue().set(trackingKey, json, TRACKING_TTL);

            // 2. Add to Redis GEO index (GEOADD) for ultra-fast spatial calculations
            // Redis GEO uses Point(longitude, latitude)
            String geoKey = "geo:partners";
            String memberKey = payload.getEffectiveId();
            redisTemplate.opsForGeo().add(geoKey, new Point(payload.getLng(), payload.getLat()), memberKey);
            redisTemplate.expire(geoKey, TRACKING_TTL);

            // 3. Push to WebSocket Topic: /topic/tracking/{bookingId}
            messagingTemplate.convertAndSend("/topic/tracking/" + bookingId, payload);
            messagingTemplate.convertAndSend("/topic/track/" + bookingId, payload);

            logger.info("Broadcasted live GPS for booking {} to /topic/tracking/{}: [{}, {}], bearing={}",
                    bookingId, bookingId, payload.getLat(), payload.getLng(), payload.getBearing());

        } catch (Exception e) {
            logger.warn("Failed to process live tracking update for booking {}: {}", bookingId, e.getMessage());
        }

        return payload;
    }

    /**
     * Retrieves the latest known location for a booking.
     */
    public LocationPayload getLatestLocation(String bookingId) {
        try {
            String trackingKey = "tracking:booking:" + bookingId;
            String json = redisTemplate.opsForValue().get(trackingKey);
            if (json != null && !json.isBlank()) {
                return objectMapper.readValue(json, LocationPayload.class);
            }
        } catch (Exception e) {
            logger.error("Error reading live location from Redis for booking {}: {}", bookingId, e.getMessage());
        }
        return null;
    }

    /**
     * Calculates distance (Haversine formula in KM) and estimated time of arrival (ETA in minutes).
     */
    public Map<String, Object> calculateDistanceAndEta(String bookingId, double customerLat, double customerLng) {
        Map<String, Object> response = new HashMap<>();
        LocationPayload workerLoc = getLatestLocation(bookingId);

        if (workerLoc == null || workerLoc.getLat() == null || workerLoc.getLng() == null) {
            response.put("found", false);
            response.put("message", "Worker live location not yet available");
            return response;
        }

        double distanceKm = haversine(workerLoc.getLat(), workerLoc.getLng(), customerLat, customerLng);
        // Estimate average urban speed: 25 km/h -> 2.4 min per km + 3 min buffer
        int etaMinutes = Math.max(1, (int) Math.round((distanceKm / 25.0) * 60.0) + 2);

        response.put("found", true);
        response.put("bookingId", bookingId);
        response.put("workerId", workerLoc.getWorkerId());
        response.put("workerLat", workerLoc.getLat());
        response.put("workerLng", workerLoc.getLng());
        response.put("bearing", workerLoc.getBearing() != null ? workerLoc.getBearing() : 0.0);
        response.put("customerLat", customerLat);
        response.put("customerLng", customerLng);
        response.put("distanceKm", Math.round(distanceKm * 100.0) / 100.0);
        response.put("etaMinutes", etaMinutes);
        response.put("lastUpdated", workerLoc.getTimestamp());

        return response;
    }

    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371; // Earth radius in km
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}
