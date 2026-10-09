package com.homeease.tracking.controller;

import com.homeease.tracking.dto.LocationPayload;
import com.homeease.tracking.service.LiveTrackingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

@Controller
public class TrackingSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(TrackingSocketHandler.class);

    private final LiveTrackingService trackingService;

    public TrackingSocketHandler(LiveTrackingService trackingService) {
        this.trackingService = trackingService;
    }

    /**
     * STOMP Destination:
     * Inbound: /app/tracking/{bookingId} or /app/track/{bookingId}
     * Broadcast to subscribers: /topic/tracking/{bookingId} & /topic/track/{bookingId}
     */
    @MessageMapping({"/tracking/{bookingId}", "/track/{bookingId}"})
    @SendTo("/topic/tracking/{bookingId}")
    public LocationPayload streamLocation(
            @DestinationVariable("bookingId") String bookingId,
            LocationPayload payload) {

        logger.debug("Live GPS stream received for booking {}: lat={}, lng={}, bearing={}", 
                bookingId, payload.getLat(), payload.getLng(), payload.getBearing());

        return trackingService.processLocationUpdate(bookingId, payload);
    }
}
