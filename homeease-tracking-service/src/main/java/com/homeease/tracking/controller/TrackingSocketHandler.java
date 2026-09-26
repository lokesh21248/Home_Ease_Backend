package com.homeease.tracking.controller;

import com.homeease.tracking.dto.LocationPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

@Controller
public class TrackingSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(TrackingSocketHandler.class);

    private final RedisTemplate<String, String> redisTemplate;

    public TrackingSocketHandler(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @MessageMapping("/track/{bookingId}")
    @SendTo("/topic/track/{bookingId}")
    public LocationPayload streamLocation(
            @DestinationVariable("bookingId") String bookingId,
            LocationPayload payload) {

        logger.debug("Tracking Microservice (Port 8082): STOMP live stream for booking {}: lat={}, lng={}", bookingId, payload.getLat(), payload.getLng());

        try {
            redisTemplate.opsForValue().set("tracking:booking:" + bookingId, payload.getLat() + "," + payload.getLng());
        } catch (Exception e) {
            logger.warn("Redis live tracking coordinate cache update failed: {}", e.getMessage());
        }

        return payload;
    }
}
