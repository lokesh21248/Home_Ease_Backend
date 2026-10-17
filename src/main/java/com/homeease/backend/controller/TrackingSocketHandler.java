package com.homeease.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.dto.WorkerDto.LocationUpdateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Controller
public class TrackingSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(TrackingSocketHandler.class);
    private static final Duration TRACKING_TTL = Duration.ofHours(2);

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public TrackingSocketHandler(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @MessageMapping("/track/{bookingId}")
    @SendTo("/topic/track/{bookingId}")
    public LocationUpdateRequest processLocationStream(
            @DestinationVariable("bookingId") String bookingId,
            LocationUpdateRequest locationPayload) {

        logger.debug("Received STOMP live location stream for booking {}: lat={}, lng={}",
                bookingId, locationPayload.getLat(), locationPayload.getLng());

        // Cache live tracking coordinates in Redis as JSON compatible with tracking-service
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("bookingId", bookingId);
            map.put("lat", locationPayload.getLat());
            map.put("lng", locationPayload.getLng());
            map.put("timestamp", System.currentTimeMillis());
            map.put("trackingActive", true);
            map.put("status", "TRACKING");

            String json = objectMapper.writeValueAsString(map);
            redisTemplate.opsForValue().set("tracking:booking:" + bookingId, json, TRACKING_TTL);
        } catch (Exception e) {
            logger.warn("Redis live tracking coordinate cache update failed: {}", e.getMessage());
        }

        // Broadcast to /topic/track/{bookingId} subscribers (Customer Mobile App)
        return locationPayload;
    }
}
