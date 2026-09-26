package com.homeease.backend.controller;

import com.homeease.backend.dto.WorkerDto.LocationUpdateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

import java.util.UUID;

@Controller
public class TrackingSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(TrackingSocketHandler.class);

    private final RedisTemplate<String, String> redisTemplate;

    public TrackingSocketHandler(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @MessageMapping("/track/{bookingId}")
    @SendTo("/topic/track/{bookingId}")
    public LocationUpdateRequest processLocationStream(
            @DestinationVariable("bookingId") String bookingId,
            LocationUpdateRequest locationPayload) {

        logger.debug("Received STOMP live location stream for booking {}: lat={}, lng={}", bookingId, locationPayload.getLat(), locationPayload.getLng());

        // Cache live tracking coordinates in Redis
        try {
            redisTemplate.opsForValue().set("tracking:booking:" + bookingId, locationPayload.getLat() + "," + locationPayload.getLng());
        } catch (Exception e) {
            logger.warn("Redis live tracking coordinate cache update failed: {}", e.getMessage());
        }

        // Broadcast to /topic/track/{bookingId} subscribers (Customer Mobile App)
        return locationPayload;
    }
}
