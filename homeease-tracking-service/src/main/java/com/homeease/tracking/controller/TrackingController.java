package com.homeease.tracking.controller;

import com.homeease.tracking.dto.LocationPayload;
import com.homeease.tracking.exception.RateLimitExceededException;
import com.homeease.tracking.security.JwtTokenValidator;
import com.homeease.tracking.service.LiveTrackingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tracking")
@CrossOrigin(origins = "*")
public class TrackingController {

    private static final Logger logger = LoggerFactory.getLogger(TrackingController.class);

    private final LiveTrackingService trackingService;
    private final JwtTokenValidator jwtValidator;

    public TrackingController(LiveTrackingService trackingService, JwtTokenValidator jwtValidator) {
        this.trackingService = trackingService;
        this.jwtValidator = jwtValidator;
    }

    /**
     * REST Partner GPS Ingestion API (called by Partner App every 3-5 seconds):
     * POST /api/v1/tracking/partner/location or POST /api/v1/tracking/update-location
     *
     * Derives worker identity from verified JWT principal or X-User-Id header.
     * Never trusts unverified client-supplied identity alone.
     */
    @PostMapping({"/partner/location", "/update-location"})
    public ResponseEntity<?> updateLiveLocationREST(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestBody LocationPayload payload) {

        if (payload == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "ERROR",
                    "error", "Request body cannot be empty"
            ));
        }

        if (payload.getBookingId() == null || payload.getBookingId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "ERROR",
                    "error", "bookingId is required"
            ));
        }

        // Authenticate service person from JWT Bearer token or X-User-Id header
        String authenticatedUserId = null;
        if (authHeader != null && !authHeader.isBlank()) {
            UUID fromJwt = jwtValidator.extractUserId(authHeader);
            if (fromJwt != null) {
                authenticatedUserId = fromJwt.toString();
            }
        }
        if (authenticatedUserId == null && headerUserId != null && !headerUserId.isBlank()) {
            authenticatedUserId = headerUserId.trim();
        }

        // Never trust client-supplied worker ID alone when authenticated principal is present
        if (authenticatedUserId != null) {
            payload.setWorkerId(authenticatedUserId);
            payload.setPartnerId(authenticatedUserId);
        }

        try {
            LocationPayload saved = trackingService.processLocationUpdate(
                    payload.getBookingId(), payload, authenticatedUserId);

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "message", "Live tracking location updated and broadcasted to WebSocket topic",
                    "data", saved
            ));
        } catch (IllegalArgumentException e) {
            logger.warn("Validation error in tracking update: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "ERROR",
                    "error", e.getMessage()
            ));
        } catch (SecurityException e) {
            logger.warn("Security violation in tracking update: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "status", "ERROR",
                    "error", e.getMessage()
            ));
        } catch (IllegalStateException e) {
            logger.info("Tracking closed for booking {}: {}", payload.getBookingId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                    "status", "INACTIVE",
                    "trackingActive", false,
                    "message", e.getMessage()
            ));
        } catch (RateLimitExceededException e) {
            logger.warn("Rate limit throttled for booking {}: {}", payload.getBookingId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                    "status", "RATE_LIMITED",
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            logger.error("Unexpected error in tracking update: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "status", "ERROR",
                    "error", "Internal server error processing live tracking update"
            ));
        }
    }

    /**
     * Fetch latest known worker location for a booking.
     */
    @GetMapping("/latest/{bookingId}")
    public ResponseEntity<?> getLatestLocation(@PathVariable("bookingId") String bookingId) {
        LocationPayload payload = trackingService.getLatestLocation(bookingId);
        if (payload == null) {
            return ResponseEntity.ok(Map.of(
                    "found", false,
                    "bookingId", bookingId,
                    "message", "No active location recorded yet for this booking"
            ));
        }

        if (Boolean.FALSE.equals(payload.getTrackingActive())) {
            return ResponseEntity.ok(Map.of(
                    "found", false,
                    "bookingId", bookingId,
                    "status", payload.getStatus() != null ? payload.getStatus() : "CLOSED",
                    "trackingActive", false,
                    "message", payload.getMessage() != null ? payload.getMessage() : "Live tracking is closed"
            ));
        }

        return ResponseEntity.ok(payload);
    }

    /**
     * Calculate live distance and ETA between worker and customer.
     */
    @GetMapping("/distance")
    public ResponseEntity<Map<String, Object>> getDistanceAndEta(
            @RequestParam("bookingId") String bookingId,
            @RequestParam("customerLat") double customerLat,
            @RequestParam("customerLng") double customerLng) {

        Map<String, Object> distanceInfo = trackingService.calculateDistanceAndEta(bookingId, customerLat, customerLng);
        return ResponseEntity.ok(distanceInfo);
    }

    /**
     * Road Routing / Directions via Google Maps (Routes API v2 / Directions API) - GET endpoint
     * GET /api/v1/tracking/route?startLat=17.4485&startLng=78.3748&destLat=17.4585&destLng=78.3848
     */
    @GetMapping({"/route", "/directions"})
    public ResponseEntity<Map<String, Object>> getDirections(
            @RequestParam("startLat") double startLat,
            @RequestParam("startLng") double startLng,
            @RequestParam("destLat") double destLat,
            @RequestParam("destLng") double destLng) {
        return ResponseEntity.ok(trackingService.getDrivingRoute(startLat, startLng, destLat, destLng));
    }

    /**
     * Road Routing / Directions via Google Maps (Routes API v2 / Directions API) - POST endpoint
     * POST /api/v1/tracking/route
     */
    @PostMapping({"/route", "/directions"})
    public ResponseEntity<Map<String, Object>> getDirectionsPost(@RequestBody Map<String, Object> body) {
        double startLat = ((Number) body.getOrDefault("startLat", 0.0)).doubleValue();
        double startLng = ((Number) body.getOrDefault("startLng", 0.0)).doubleValue();
        double destLat = ((Number) body.getOrDefault("destLat", 0.0)).doubleValue();
        double destLng = ((Number) body.getOrDefault("destLng", 0.0)).doubleValue();
        return ResponseEntity.ok(trackingService.getDrivingRoute(startLat, startLng, destLat, destLng));
    }
}
