package com.homeease.tracking.controller;

import com.homeease.tracking.config.TrackingStompSecurityInterceptor;
import com.homeease.tracking.dto.LocationPayload;
import com.homeease.tracking.service.LiveTrackingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;

@Controller
public class TrackingSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(TrackingSocketHandler.class);

    private final LiveTrackingService trackingService;
    private final SimpMessagingTemplate messagingTemplate;

    public TrackingSocketHandler(LiveTrackingService trackingService, SimpMessagingTemplate messagingTemplate) {
        this.trackingService = trackingService;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * STOMP Destination:
     * Inbound: /app/tracking/{bookingId} or /app/track/{bookingId}
     * Broadcast to subscribers: /topic/tracking/{bookingId} & /topic/track/{bookingId}
     *
     * Note: Only a session authenticated with a cryptographically verified JWT
     * ({@code ATTR_VERIFIED == true}) provides an authenticated user identity.
     * Unverified X-User-Id header identities are never forwarded as verified callers.
     */
    @MessageMapping({"/tracking/{bookingId}", "/track/{bookingId}"})
    public void streamLocation(
            @DestinationVariable("bookingId") String bookingId,
            LocationPayload payload,
            Principal principal,
            SimpMessageHeaderAccessor headerAccessor) {

        String authenticatedUserId = resolveVerifiedUserId(principal, headerAccessor);

        logger.debug("Live GPS stream received for booking {}: verifiedPrincipal={}",
                bookingId, authenticatedUserId != null);

        if (payload != null) {
            // Never allow client-supplied workerId/partnerId to survive into ingest.
            payload.setWorkerId(authenticatedUserId);
            payload.setPartnerId(authenticatedUserId);
            trackingService.processLocationUpdate(bookingId, payload, authenticatedUserId);
        }
    }

    /**
     * Reconnection Snapshot:
     * Triggers an immediate broadcast of the latest location for verified session participants.
     */
    @MessageMapping({"/tracking/{bookingId}/snapshot", "/track/{bookingId}/snapshot"})
    public void requestSnapshot(
            @DestinationVariable("bookingId") String bookingId,
            Principal principal,
            SimpMessageHeaderAccessor headerAccessor) {
        if (resolveVerifiedUserId(principal, headerAccessor) == null) {
            logger.warn("Rejected unverified STOMP snapshot request for booking {}", bookingId);
            return;
        }
        LocationPayload latest = trackingService.getLatestLocation(bookingId);
        if (latest != null) {
            messagingTemplate.convertAndSend("/topic/tracking/" + bookingId, latest);
            messagingTemplate.convertAndSend("/topic/track/" + bookingId, latest);
            logger.info("Sent reconnection snapshot for booking {}", bookingId);
        }
    }

    private static String resolveVerifiedUserId(Principal principal, SimpMessageHeaderAccessor headerAccessor) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            return null;
        }
        Map<String, Object> session = headerAccessor != null ? headerAccessor.getSessionAttributes() : null;
        boolean verified = session != null && Boolean.TRUE.equals(session.get(TrackingStompSecurityInterceptor.ATTR_VERIFIED));
        return verified ? principal.getName().trim() : null;
    }
}
