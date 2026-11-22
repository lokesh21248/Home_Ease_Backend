package com.homeease.tracking.controller;

import com.homeease.tracking.dto.LocationPayload;
import com.homeease.tracking.exception.RateLimitExceededException;
import com.homeease.tracking.security.JwtTokenValidator;
import com.homeease.tracking.security.TrackingAccessGuard;
import com.homeease.tracking.service.LiveTrackingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping({ "/api/v1/tracking", "/api/tracking" })
@CrossOrigin(origins = "*")
public class TrackingController {

    private static final Logger logger = LoggerFactory.getLogger(TrackingController.class);

    private final LiveTrackingService trackingService;
    private final JwtTokenValidator jwtValidator;
    private final TrackingAccessGuard accessGuard;

    public TrackingController(LiveTrackingService trackingService,
            JwtTokenValidator jwtValidator,
            TrackingAccessGuard accessGuard) {
        this.trackingService = trackingService;
        this.jwtValidator = jwtValidator;
        this.accessGuard = accessGuard;
    }

    // NOTE: POST /{bookingId}/start is NOT served here. Starting a trip is a booking state change (ACCEPTED ->
    // ON_THE_WAY) owned by the core service (POST /api/v1/tracking/{bookingId}/start or /bookings/{id}/on-the-way);
    // Nginx routes it there. The previous handler here returned "trackingActive: true" without doing anything.

    /**
     * REST Partner GPS Ingestion API (called by Partner App every 3-5 seconds):
     * POST /api/v1/tracking/partner/location, POST
     * /api/tracking/{bookingId}/location
     */
    @PostMapping({ "/partner/location", "/update-location", "/{bookingId}/location" })
    public ResponseEntity<?> updateLiveLocationREST(
            @PathVariable(value = "bookingId", required = false) String pathBookingId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestBody LocationPayload payload) {

        if (payload == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "ERROR",
                    "error", "Request body cannot be empty"));
        }

        if (pathBookingId != null && !pathBookingId.isBlank()) {
            payload.setBookingId(pathBookingId);
        }

        if (payload.getBookingId() == null || payload.getBookingId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "ERROR",
                    "error", "bookingId is required"));
        }

        // Identity ONLY from a verified JWT. Missing/invalid token -> 401 JWT_REQUIRED.
        // If an administrator is orchestrating or testing, allow acting on behalf of the assigned worker.
        var caller = accessGuard.requireVerifiedJwt(authHeader);
        String authenticatedUserId = caller.isAdmin() && headerUserId != null && !headerUserId.isBlank()
                ? headerUserId.trim()
                : caller.userId();
        payload.setWorkerId(authenticatedUserId);
        payload.setPartnerId(authenticatedUserId);

        try {
            LocationPayload saved = trackingService.processLocationUpdate(
                    payload.getBookingId(), payload, authenticatedUserId);

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "message", "Live tracking location updated and broadcasted to WebSocket topic",
                    "data", saved));
        } catch (IllegalArgumentException e) {
            logger.warn("Validation error in tracking update: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "ERROR",
                    "error", e.getMessage()));
        } catch (SecurityException e) {
            logger.warn("Security violation in tracking update: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "status", "ERROR",
                    "error", e.getMessage()));
        } catch (IllegalStateException e) {
            logger.info("Tracking closed for booking {}: {}", payload.getBookingId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                    "status", "INACTIVE",
                    "trackingActive", false,
                    "message", e.getMessage()));
        } catch (RateLimitExceededException e) {
            logger.warn("Rate limit throttled for booking {}: {}", payload.getBookingId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                    "status", "RATE_LIMITED",
                    "error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Unexpected error in tracking update: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "status", "ERROR",
                    "error", "Internal server error processing live tracking update"));
        }
    }

    /**
     * Fetch latest known worker location for a booking.
     * Requires a verified JWT belonging to the booking's customer, its assigned
     * worker, or an admin.
     * GET /api/tracking/{bookingId}/location or GET
     * /api/v1/tracking/latest/{bookingId}
     */
    @GetMapping({ "/latest/{bookingId}", "/{bookingId}/location" })
    public ResponseEntity<?> getLatestLocation(
            @PathVariable("bookingId") String bookingId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        accessGuard.requireParticipant(authHeader, bookingId);
        LocationPayload payload = trackingService.getLatestLocation(bookingId);
        if (payload == null) {
            return ResponseEntity.ok(Map.of(
                    "found", false,
                    "bookingId", bookingId,
                    "message", "No active location recorded yet for this booking"));
        }

        if (Boolean.FALSE.equals(payload.getTrackingActive())) {
            return ResponseEntity.ok(Map.of(
                    "found", false,
                    "bookingId", bookingId,
                    "status", payload.getStatus() != null ? payload.getStatus() : "CLOSED",
                    "trackingActive", false,
                    "message", payload.getMessage() != null ? payload.getMessage() : "Live tracking is closed"));
        }

        return ResponseEntity.ok(payload);
    }

    /**
     * Explicit stop/close live tracking for a booking.
     * POST /api/tracking/{bookingId}/stop or POST /api/v1/tracking/stop/{bookingId}
     */
    @PostMapping({ "/stop/{bookingId}", "/close/{bookingId}", "/{bookingId}/stop" })
    public ResponseEntity<?> stopTracking(
            @PathVariable("bookingId") String bookingId,
            @RequestParam(value = "reason", defaultValue = "COMPLETED") String reason,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        accessGuard.requireAssignedWorker(authHeader, bookingId);
        trackingService.stopTracking(bookingId, reason);
        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "bookingId", bookingId,
                "trackingActive", false,
                "message", "Live tracking stopped for booking " + bookingId));
    }

    /**
     * Calculate live distance and ETA between worker and customer.
     */
    @GetMapping("/distance")
    public ResponseEntity<Map<String, Object>> getDistanceAndEta(
            @RequestParam("bookingId") String bookingId,
            @RequestParam("customerLat") double customerLat,
            @RequestParam("customerLng") double customerLng,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        accessGuard.requireParticipant(authHeader, bookingId);
        // NOTE: distance/ETA here are straight-line (haversine) estimates, not road
        // data. Deprecated for display:
        // the customer app shows only provider route distance/duration from GET
        // /tracking/{bookingId}/route.
        Map<String, Object> distanceInfo = trackingService.calculateDistanceAndEta(bookingId, customerLat, customerLng);
        return ResponseEntity.ok(distanceInfo);
    }

    // REMOVED: GET /api/v1/tracking/{bookingId}/route. It took the destination from
    // client query parameters,
    // had no authorization, and read no persisted booking data. The authoritative
    // endpoint of the same path now
    // lives in the core service (booking-authorized, persisted destination) and
    // Nginx routes it there.

    /**
     * Coordinate-based road routing used by the WORKER app's own navigation screen.
     * GET /api/v1/tracking/route?startLat=..&startLng=..&destLat=..&destLng=..
     *
     * <p>
     * This proxies a paid provider call for arbitrary coordinates, so it is no
     * longer anonymous: a caller
     * identity is required and usage is capped per identity and globally. Customer
     * tracking must NOT use it.
     * </p>
     */
    @GetMapping({ "/route", "/directions" })
    public ResponseEntity<Map<String, Object>> getDirections(
            @RequestParam("startLat") double startLat,
            @RequestParam("startLng") double startLng,
            @RequestParam("destLat") double destLat,
            @RequestParam("destLng") double destLng,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId) {
        accessGuard.checkRoutePermit(accessGuard.requireRouteIdentity(authHeader, headerUserId));
        return ResponseEntity.ok(trackingService.getDrivingRoute(startLat, startLng, destLat, destLng));
    }

    /**
     * POST variant of the worker-navigation coordinate route. Same protections as
     * the GET variant.
     */
    @PostMapping({ "/route", "/directions" })
    public ResponseEntity<Map<String, Object>> getDirectionsPost(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId) {
        accessGuard.checkRoutePermit(accessGuard.requireRouteIdentity(authHeader, headerUserId));
        double startLat = requiredNumber(body, "startLat");
        double startLng = requiredNumber(body, "startLng");
        double destLat = requiredNumber(body, "destLat");
        double destLng = requiredNumber(body, "destLng");
        return ResponseEntity.ok(trackingService.getDrivingRoute(startLat, startLng, destLat, destLng));
    }

    private static double requiredNumber(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (!(v instanceof Number n) || !Double.isFinite(n.doubleValue())) {
            throw new IllegalArgumentException(key + " is required and must be a number");
        }
        return n.doubleValue();
    }

    /**
     * DSA 3: Fetch recent GPS breadcrumbs history (bounded to last 20 points).
     * GET /api/v1/tracking/history/{bookingId}
     */
    @GetMapping("/history/{bookingId}")
    public ResponseEntity<?> getTrackingHistory(
            @PathVariable("bookingId") String bookingId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        accessGuard.requireParticipant(authHeader, bookingId);
        var history = trackingService.getRecentHistory(bookingId);
        return ResponseEntity.ok(Map.of(
                "bookingId", bookingId,
                "count", history.size(),
                "history", history));
    }

    /**
     * DSA 8: Nearest Available Worker Proximity Search using Redis GEO.
     * GET /api/v1/tracking/nearby?lat=17.4485&lng=78.3748&radiusKm=5.0&limit=10
     */
    @GetMapping("/nearby")
    public ResponseEntity<?> getNearbyWorkers(
            @RequestParam("lat") double lat,
            @RequestParam("lng") double lng,
            @RequestParam(value = "radiusKm", defaultValue = "5.0") double radiusKm,
            @RequestParam(value = "limit", defaultValue = "10") int limit,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        // Exposes every online worker's coordinates; was unauthenticated.
        accessGuard.requireAdmin(authHeader);
        var workers = trackingService.findNearestWorkers(lat, lng, radiusKm, limit);
        return ResponseEntity.ok(Map.of(
                "centerLat", lat,
                "centerLng", lng,
                "radiusKm", radiusKm,
                "count", workers.size(),
                "workers", workers));
    }

    /**
     * Real-Time Telemetry and Queue Health Stats.
     * GET /api/v1/tracking/stats
     */
    @GetMapping("/stats")
    public ResponseEntity<?> getTrackingStats(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        accessGuard.requireAdmin(authHeader);
        return ResponseEntity.ok(Map.of(
                "activeSessions", trackingService.getActiveSessionsCount(),
                "queueSize", trackingService.getEventQueueSize(),
                "processedEvents", trackingService.getProcessedEventsCount(),
                "droppedEvents", trackingService.getDroppedEventsCount()));
    }
}
