package com.homeease.backend.controller;

import com.homeease.backend.dto.BookingRouteResponse;
import com.homeease.backend.dto.TrackingDto.*;
import com.homeease.backend.dto.WorkerDto.LocationUpdateRequest;
import com.homeease.backend.exception.RoutingException;
import com.homeease.backend.routing.BookingRouteService;
import com.homeease.backend.routing.RouteRefreshReason;
import com.homeease.backend.security.JwtService;
import com.homeease.backend.security.VerifiedCaller;
import com.homeease.backend.service.TrackingService;
import com.homeease.backend.tracking.DeviceStatusStore;
import com.homeease.backend.tracking.TrackingPhaseResolver.DeviceGpsState;
import com.homeease.backend.tracking.TrackingSnapshotService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping({ "/api/v1/tracking", "/api/tracking" })
@CrossOrigin(origins = "*")
public class TrackingController {

    private static final Logger logger = LoggerFactory.getLogger(TrackingController.class);

    private final TrackingService trackingService;
    private final JwtService jwtService;
    private final BookingRouteService bookingRouteService;
    private final TrackingSnapshotService snapshotService;
    private final DeviceStatusStore deviceStatusStore;

    /** Standalone test wiring overload (route/basic controller tests). */
    public TrackingController(TrackingService trackingService, JwtService jwtService,
            BookingRouteService bookingRouteService) {
        this(trackingService, jwtService, bookingRouteService, null, null);
    }

    @Autowired
    public TrackingController(TrackingService trackingService, JwtService jwtService,
            BookingRouteService bookingRouteService, TrackingSnapshotService snapshotService,
            DeviceStatusStore deviceStatusStore) {
        this.trackingService = trackingService;
        this.jwtService = jwtService;
        this.bookingRouteService = bookingRouteService;
        this.snapshotService = snapshotService;
        this.deviceStatusStore = deviceStatusStore;
    }

    /**
     * Authoritative tracking snapshot for customer/partner tracking screens:
     * booking phase, live vs stale GPS flag, latest fix, destination, partner info, and doorstep PIN visibility.
     */
    @GetMapping("/{bookingId:[0-9a-fA-F\\-]{36}}")
    public ResponseEntity<Map<String, Object>> getTracking(@PathVariable("bookingId") UUID bookingId) {
        if (snapshotService == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(snapshotService.snapshot(bookingId, VerifiedCaller.current()));
    }

    /**
     * Durable GPS breadcrumbs from PostgreSQL, oldest first.
     */
    @GetMapping("/{bookingId:[0-9a-fA-F\\-]{36}}/history")
    public ResponseEntity<Map<String, Object>> getHistory(
            @PathVariable("bookingId") UUID bookingId,
            @RequestParam(value = "limit", defaultValue = "500") int limit) {
        if (snapshotService == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(snapshotService.history(bookingId, VerifiedCaller.current(), limit));
    }

    /**
     * Partner app reports permission/GPS device state (OK | PERMISSION_DENIED | GPS_DISABLED).
     */
    @PostMapping("/{bookingId:[0-9a-fA-F\\-]{36}}/device-status")
    public ResponseEntity<Map<String, Object>> reportDeviceStatus(
            @PathVariable("bookingId") UUID bookingId,
            @RequestBody(required = false) Map<String, Object> body) {
        if (snapshotService == null || deviceStatusStore == null) {
            return ResponseEntity.notFound().build();
        }
        snapshotService.requireAssignedWorker(bookingId, VerifiedCaller.current());
        DeviceGpsState state;
        try {
            state = DeviceGpsState.valueOf(
                    String.valueOf(body == null ? null : body.get("state")).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("state must be one of OK, PERMISSION_DENIED, GPS_DISABLED");
        }
        if (state == DeviceGpsState.UNKNOWN) {
            throw new IllegalArgumentException("state must be one of OK, PERMISSION_DENIED, GPS_DISABLED");
        }
        deviceStatusStore.report(bookingId, state);
        return ResponseEntity.ok(Map.of("bookingId", bookingId, "deviceGpsState", state.name()));
    }

    /**
     * Starts tracking session when worker begins travelling.
     * POST /api/tracking/{bookingId}/start or POST
     * /api/v1/tracking/{bookingId}/start
     */
    @PostMapping("/{bookingId}/start")
    public ResponseEntity<TrackingSessionDto> startTracking(
            @PathVariable("bookingId") UUID bookingId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "userId", required = false) UUID queryUserId) {
        UUID workerUserId = resolveUserId(authHeader, headerUserId, queryUserId);
        TrackingSessionDto session = trackingService.startTravellingSession(bookingId, workerUserId);
        return ResponseEntity.ok(session);
    }

    /**
     * Ingests high-frequency live GPS updates from worker during travel.
     * Rejects updates when session is ARRIVED or STOPPED.
     * POST /api/tracking/{bookingId}/location or POST
     * /api/v1/tracking/partner/location
     */
    @PostMapping({ "/partner/location", "/update-location", "/{bookingId}/location" })
    public ResponseEntity<LocationUpdateResult> updateLiveLocation(
            @PathVariable(value = "bookingId", required = false) UUID pathBookingId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @Valid @RequestBody LocationUpdateRequest request) {

        UUID workerUserId = resolveUserId(authHeader, headerUserId, queryUserId);
        UUID bookingId = pathBookingId;

        if (bookingId == null && request.getBookingId() != null && !request.getBookingId().isBlank()) {
            try {
                bookingId = UUID.fromString(request.getBookingId().trim());
            } catch (Exception ignored) {
            }
        }

        if (bookingId == null) {
            return ResponseEntity.badRequest().body(LocationUpdateResult.builder()
                    .success(false)
                    .status("ERROR")
                    .trackingActive(false)
                    .message("bookingId is required")
                    .build());
        }

        if (request.getTimestamp() == null || request.getTimestamp() <= 0) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY).body(LocationUpdateResult.builder()
                    .success(false)
                    .status("UNPROCESSABLE_ENTITY")
                    .trackingActive(false)
                    .message("Device timestamp is required to order and check fixes for replay")
                    .build());
        }

        LocationUpdateResult result = trackingService.updateWorkerLocation(bookingId, workerUserId, request);
        if (!result.isSuccess() && ("ARRIVED".equals(result.getStatus()) || "STOPPED".equals(result.getStatus()))) {
            return ResponseEntity.status(409).body(result);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Returns the latest position for a given bookingId.
     * Returns trackingActive = false when session is ARRIVED or STOPPED.
     * GET /api/tracking/{bookingId}/location or GET
     * /api/v1/tracking/latest/{bookingId}
     */
    @GetMapping({ "/latest/{bookingId}", "/{bookingId}/location" })
    public ResponseEntity<TrackingLocationResponse> getLatestLocation(
            @PathVariable("bookingId") UUID bookingId) {
        return ResponseEntity.ok(trackingService.getLatestLocation(bookingId));
    }

    /**
     * Explicitly stops tracking for a booking (e.g. upon arrival or job
     * completion).
     * POST /api/tracking/{bookingId}/stop or POST /api/v1/tracking/{bookingId}/stop
     */
    @PostMapping({ "/{bookingId}/stop", "/stop/{bookingId}" })
    public ResponseEntity<TrackingSessionDto> stopTracking(
            @PathVariable("bookingId") UUID bookingId,
            @RequestParam(value = "reason", defaultValue = "STOPPED") String reason) {
        if (snapshotService != null) {
            snapshotService.requireAssignedWorker(bookingId, VerifiedCaller.current());
        }
        TrackingSessionDto session = trackingService.stopTracking(bookingId, reason);
        return ResponseEntity.ok(session);
    }

    /**
     * Calculates distance & estimated time of arrival between worker and customer.
     * Automatically returns 0 distance/ETA when ARRIVED.
     */
    @GetMapping("/distance")
    public ResponseEntity<TrackingDistanceResponse> getDistanceAndEta(
            @RequestParam("bookingId") UUID bookingId,
            @RequestParam("customerLat") double customerLat,
            @RequestParam("customerLng") double customerLng) {
        return ResponseEntity.ok(trackingService.getDistanceAndEta(bookingId, customerLat, customerLng));
    }

    /**
     * Road route from the assigned worker's latest GPS fix to the booking's
     * persisted service address.
     *
     * <p>
     * GET /api/v1/tracking/{bookingId}/route[?reason=INITIAL|PERIODIC|OFF_ROUTE]
     * </p>
     *
     * <p>
     * The request deliberately carries no coordinates. Identity comes from a
     * cryptographically verified
     * JWT only - the legacy "Bearer &lt;uuid&gt; / X-User-Id" fallback in
     * {@code JwtAuthenticationFilter} is
     * NOT honoured here, so knowing a participant's UUID is never enough to read a
     * route.
     * </p>
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

    /**
     * Retrieves the authoritative tracking session metadata for the booking.
     */
    @GetMapping("/session/{bookingId}")
    public ResponseEntity<TrackingSessionDto> getSession(
            @PathVariable("bookingId") UUID bookingId) {
        TrackingSessionDto session = trackingService.getSession(bookingId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(session);
    }

    private UUID resolveUserId(String authHeader, String headerUserId, UUID queryUserId) {
        return com.homeease.backend.security.SecurityUtils.resolveAuthenticatedUserId(queryUserId, headerUserId);
    }
}
