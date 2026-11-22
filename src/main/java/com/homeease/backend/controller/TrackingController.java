package com.homeease.backend.controller;

import com.homeease.backend.dto.BookingRouteResponse;
import com.homeease.backend.dto.TrackingDto.*;
import com.homeease.backend.dto.WorkerDto.LocationUpdateRequest;
import com.homeease.backend.exception.RoutingException;
import com.homeease.backend.exception.TrackingApiException;
import com.homeease.backend.routing.BookingRouteService;
import com.homeease.backend.routing.RouteRefreshReason;
import com.homeease.backend.security.JwtService;
import com.homeease.backend.security.VerifiedCaller;
import com.homeease.backend.service.BookingAssignmentService;
import com.homeease.backend.service.TrackingService;
import com.homeease.backend.tracking.DeviceStatusStore;
import com.homeease.backend.tracking.TrackingPhaseResolver.DeviceGpsState;
import com.homeease.backend.tracking.TrackingSnapshotService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Live-tracking API of the core service.
 *
 * <p>Every endpoint here identifies the caller from a <b>verified JWT</b> ({@link VerifiedCaller}) and checks the
 * caller against the persisted booking (customer, assigned partner, or admin). The legacy "Bearer &lt;uuid&gt;" /
 * {@code X-User-Id} / {@code ?userId=} identities are not accepted: previously several of these endpoints returned
 * any booking's live partner location (or ended its tracking) to any signed-in user.</p>
 *
 * <p>Ownership in production (see nginx/conf.d/homeease_tracking.conf): GPS ingestion, the STOMP socket and the
 * in-memory breadcrumbs belong to the tracking service; this controller owns the booking-aware views (snapshot,
 * route, PostgreSQL history, device status). The ingest/latest endpoints below remain for clients that reach the
 * core directly and apply the same rules.</p>
 */
@RestController
@RequestMapping({"/api/v1/tracking", "/api/tracking"})
@CrossOrigin(origins = "*")
public class TrackingController {

    private static final Set<String> STOP_REASONS = Set.of("ARRIVED", "COMPLETED", "CANCELLED", "STOPPED");

    private final TrackingService trackingService;
    private final JwtService jwtService;
    private final BookingRouteService bookingRouteService;
    private final TrackingSnapshotService snapshotService;
    private final BookingAssignmentService assignmentService;
    private final DeviceStatusStore deviceStatusStore;

    /** Route-only wiring (used by the route controller test). */
    public TrackingController(TrackingService trackingService, JwtService jwtService,
                              BookingRouteService bookingRouteService) {
        this(trackingService, jwtService, bookingRouteService, null, null, null);
    }

    @Autowired
    public TrackingController(TrackingService trackingService, JwtService jwtService,
                              BookingRouteService bookingRouteService, TrackingSnapshotService snapshotService,
                              BookingAssignmentService assignmentService, DeviceStatusStore deviceStatusStore) {
        this.trackingService = trackingService;
        this.jwtService = jwtService;
        this.bookingRouteService = bookingRouteService;
        this.snapshotService = snapshotService;
        this.assignmentService = assignmentService;
        this.deviceStatusStore = deviceStatusStore;
    }

    // ───────────────────────── snapshot / history ─────────────────────────

    /**
     * GET /api/v1/tracking/{bookingId} - the authoritative tracking view: phase (state machine projection), whether
     * the location is genuinely live, latest fix, destination, partner card, PIN availability (customer only).
     */
    @GetMapping("/{bookingId}")
    public ResponseEntity<Map<String, Object>> getTracking(@PathVariable("bookingId") UUID bookingId) {
        return noStore(snapshotService.snapshot(bookingId, VerifiedCaller.current()));
    }

    /** GET /api/v1/tracking/{bookingId}/history?limit=500 - durable breadcrumbs from PostgreSQL, oldest first. */
    @GetMapping("/{bookingId}/history")
    public ResponseEntity<Map<String, Object>> getHistory(
            @PathVariable("bookingId") UUID bookingId,
            @RequestParam(value = "limit", defaultValue = "500") int limit) {
        return noStore(snapshotService.history(bookingId, VerifiedCaller.current(), limit));
    }

    /**
     * POST /api/v1/tracking/{bookingId}/device-status {"state":"PERMISSION_DENIED|GPS_DISABLED|OK"} - the partner
     * app reports why it cannot share location, so the customer sees the real reason instead of a frozen marker.
     */
    @PostMapping("/{bookingId}/device-status")
    public ResponseEntity<Map<String, Object>> reportDeviceStatus(
            @PathVariable("bookingId") UUID bookingId,
            @RequestBody Map<String, Object> body) {
        snapshotService.requireAssignedWorker(bookingId, VerifiedCaller.current());
        DeviceGpsState state;
        try {
            state = DeviceGpsState.valueOf(String.valueOf(body == null ? null : body.get("state")).trim()
                    .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("state must be one of OK, PERMISSION_DENIED, GPS_DISABLED");
        }
        if (state == DeviceGpsState.UNKNOWN) {
            throw new IllegalArgumentException("state must be one of OK, PERMISSION_DENIED, GPS_DISABLED");
        }
        deviceStatusStore.report(bookingId, state);
        return ResponseEntity.ok(Map.of("bookingId", bookingId, "deviceGpsState", state.name()));
    }

    // ───────────────────────── travel lifecycle ─────────────────────────

    /**
     * POST /api/v1/tracking/{bookingId}/start - the assigned partner starts travelling. Uses the same state transition
     * as POST /bookings/{id}/on-the-way (ACCEPTED/CONFIRMED -> ON_THE_WAY) and returns the tracking view.
     */
    @PostMapping("/{bookingId}/start")
    public ResponseEntity<Map<String, Object>> startTracking(@PathVariable("bookingId") UUID bookingId) {
        VerifiedCaller caller = VerifiedCaller.current();
        snapshotService.requireAssignedWorker(bookingId, caller);
        assignmentService.handleWorkerStartTravel(bookingId, caller.userId());
        return noStore(snapshotService.snapshot(bookingId, caller));
    }

    /**
     * Partner GPS ingestion on the core. Production routes these paths to the tracking service; this copy applies
     * the same rules (verified JWT, assigned partner, validated fix).
     */
    @PostMapping({"/partner/location", "/update-location", "/{bookingId}/location"})
    public ResponseEntity<LocationUpdateResult> updateLiveLocation(
            @PathVariable(value = "bookingId", required = false) UUID pathBookingId,
            @Valid @RequestBody LocationUpdateRequest request) {

        VerifiedCaller caller = VerifiedCaller.current();
        UUID bookingId = pathBookingId;
        if (bookingId == null && request.getBookingId() != null && !request.getBookingId().isBlank()) {
            try {
                bookingId = UUID.fromString(request.getBookingId().trim());
            } catch (IllegalArgumentException ignored) {
                // reported below
            }
        }
        if (bookingId == null) {
            return ResponseEntity.badRequest().body(LocationUpdateResult.builder()
                    .success(false).status("ERROR").trackingActive(false).message("bookingId is required").build());
        }
        snapshotService.requireAssignedWorker(bookingId, caller);

        LocationUpdateResult result = trackingService.updateWorkerLocation(bookingId, caller.userId(), request);
        if (!result.isSuccess() && ("ARRIVED".equals(result.getStatus()) || "STOPPED".equals(result.getStatus()))) {
            return ResponseEntity.status(409).body(result);
        }
        if (!result.isSuccess() && "REJECTED".equals(result.getStatus())) {
            return ResponseEntity.unprocessableEntity().body(result);
        }
        return ResponseEntity.ok(result);
    }

    /** Latest partner position; participants only. Never substitutes the customer's address for the partner. */
    @GetMapping({"/latest/{bookingId}", "/{bookingId}/location"})
    public ResponseEntity<TrackingLocationResponse> getLatestLocation(@PathVariable("bookingId") UUID bookingId) {
        snapshotService.requireParticipant(bookingId, VerifiedCaller.current());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(trackingService.getLatestLocation(bookingId));
    }

    /** Ends live tracking (assigned partner or admin). The reason is restricted to known values. */
    @PostMapping({"/{bookingId}/stop", "/stop/{bookingId}"})
    public ResponseEntity<TrackingSessionDto> stopTracking(
            @PathVariable("bookingId") UUID bookingId,
            @RequestParam(value = "reason", defaultValue = "STOPPED") String reason) {
        snapshotService.requireAssignedWorker(bookingId, VerifiedCaller.current());
        String normalized = reason == null ? "STOPPED" : reason.trim().toUpperCase(Locale.ROOT);
        if (!STOP_REASONS.contains(normalized)) {
            throw new IllegalArgumentException("reason must be one of " + STOP_REASONS);
        }
        return ResponseEntity.ok(trackingService.stopTracking(bookingId, normalized));
    }

    /**
     * Straight-line distance from the partner to the booking's PERSISTED address (the client-supplied coordinates
     * are ignored). No ETA: an ETA without a road route would be invented. Use GET /{bookingId}/route.
     */
    @GetMapping("/distance")
    public ResponseEntity<TrackingDistanceResponse> getDistance(
            @RequestParam("bookingId") UUID bookingId,
            @RequestParam(value = "customerLat", required = false) Double ignoredLat,
            @RequestParam(value = "customerLng", required = false) Double ignoredLng) {
        snapshotService.requireParticipant(bookingId, VerifiedCaller.current());
        return ResponseEntity.ok(trackingService.getStraightLineDistance(bookingId));
    }

    /** Tracking session metadata; participants only. */
    @GetMapping("/session/{bookingId}")
    public ResponseEntity<TrackingSessionDto> getSession(@PathVariable("bookingId") UUID bookingId) {
        snapshotService.requireParticipant(bookingId, VerifiedCaller.current());
        TrackingSessionDto session = trackingService.getSession(bookingId);
        return session == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(session);
    }

    // ───────────────────────── road route (unchanged contract) ─────────────────────────

    /**
     * Road route from the assigned worker's latest GPS fix to the booking's persisted service address.
     *
     * <p>GET /api/v1/tracking/{bookingId}/route[?reason=INITIAL|PERIODIC|OFF_ROUTE]</p>
     *
     * <p>The request deliberately carries no coordinates. Identity comes from a cryptographically verified JWT only.</p>
     */
    @GetMapping("/{bookingId}/route")
    public ResponseEntity<BookingRouteResponse> getBookingRoute(
            @PathVariable("bookingId") UUID bookingId,
            @RequestParam(value = "reason", required = false) String reason,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Map<String, Object> claims = jwtService.validateAndExtractClaims(authHeader);
        UUID callerId = claims == null ? null : verifiedUserId(claims);
        if (callerId == null) {
            throw new RoutingException(RoutingException.Code.JWT_REQUIRED, "A valid sign-in is required.");
        }
        boolean admin = "ADMIN".equalsIgnoreCase(String.valueOf(claims.get("role")));

        BookingRouteResponse response = bookingRouteService.getRoute(
                bookingId, callerId, admin, RouteRefreshReason.parse(reason));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    private static UUID verifiedUserId(Map<String, Object> claims) {
        Object raw = claims.get("userId") != null ? claims.get("userId") : claims.get("sub");
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @SuppressWarnings("unused")
    private static TrackingApiException unused() {
        return null;
    }
}
