package com.homeease.backend.controller;

import com.homeease.backend.dto.TrackingDto.LocationUpdateResult;
import com.homeease.backend.dto.WorkerDto.LocationUpdateRequest;
import com.homeease.backend.service.TrackingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.UUID;

@Controller
public class TrackingSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(TrackingSocketHandler.class);

    private final TrackingService trackingService;
    private final SimpMessagingTemplate messagingTemplate;

    @Autowired
    public TrackingSocketHandler(TrackingService trackingService, SimpMessagingTemplate messagingTemplate) {
        this.trackingService = trackingService;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * STOMP Destination: /app/track/{bookingId}
     * Broadcasts to: /topic/track/{bookingId}
     */
    @MessageMapping("/track/{bookingId}")
    public void processLocationStream(
            @DestinationVariable("bookingId") String bookingIdStr,
            @Payload LocationUpdateRequest locationPayload) {

        UUID bookingId;
        try {
            bookingId = UUID.fromString(bookingIdStr.trim());
        } catch (Exception e) {
            logger.warn("Invalid bookingId in STOMP track: {}", bookingIdStr);
            return;
        }

        UUID workerUserId = null;
        if (locationPayload.getWorkerId() != null) {
            try { workerUserId = UUID.fromString(locationPayload.getWorkerId().trim()); } catch (Exception ignored) {}
        }

        LocationUpdateResult result = trackingService.updateWorkerLocation(bookingId, workerUserId, locationPayload);
        if (!result.isSuccess()) {
            logger.debug("STOMP location stream ignored for booking {}: {}", bookingId, result.getMessage());
        }
    }

    /**
     * STOMP Destination: /app/tracking/update
     * Handles coordinate payloads with embedded bookingId.
     */
    @MessageMapping("/tracking/update")
    public void processGeneralTrackingStream(@Payload LocationUpdateRequest locationPayload) {
        if (locationPayload.getBookingId() == null || locationPayload.getBookingId().isBlank()) {
            return;
        }

        UUID bookingId;
        try {
            bookingId = UUID.fromString(locationPayload.getBookingId().trim());
        } catch (Exception e) {
            return;
        }

        UUID workerUserId = null;
        if (locationPayload.getWorkerId() != null) {
            try { workerUserId = UUID.fromString(locationPayload.getWorkerId().trim()); } catch (Exception ignored) {}
        }

        trackingService.updateWorkerLocation(bookingId, workerUserId, locationPayload);
    }
}
