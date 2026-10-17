package com.homeease.tracking.controller;

import com.homeease.tracking.dto.LocationPayload;
import com.homeease.tracking.service.LiveTrackingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;

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
     *
     * Note: Duplicate broadcast is eliminated by removing @SendTo;
     * LiveTrackingService explicitly broadcasts via SimpMessagingTemplate to both topics.
     */
    @MessageMapping({"/tracking/{bookingId}", "/track/{bookingId}"})
    public void streamLocation(
            @DestinationVariable("bookingId") String bookingId,
            LocationPayload payload,
            Principal principal) {

        String authenticatedUserId = principal != null ? principal.getName() : null;

        logger.debug("Live GPS stream received for booking {}: lat={}, lng={}, bearing={}, principal={}",
                bookingId, payload != null ? payload.getLat() : null,
                payload != null ? payload.getLng() : null,
                payload != null ? payload.getBearing() : null,
                authenticatedUserId);

        if (payload != null) {
            trackingService.processLocationUpdate(bookingId, payload, authenticatedUserId);
        }
    }
}
