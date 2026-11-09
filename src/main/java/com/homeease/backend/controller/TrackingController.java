package com.homeease.backend.controller;

import com.homeease.backend.dto.TrackingDto.*;
import com.homeease.backend.dto.WorkerDto.LocationUpdateRequest;
import com.homeease.backend.security.JwtService;
import com.homeease.backend.service.TrackingService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping({"/api/v1/tracking", "/api/tracking"})
@CrossOrigin(origins = "*")
public class TrackingController {

    private static final Logger logger = LoggerFactory.getLogger(TrackingController.class);

    private final TrackingService trackingService;
    private final JwtService jwtService;

    @Autowired
    public TrackingController(TrackingService trackingService, JwtService jwtService) {
        this.trackingService = trackingService;
        this.jwtService = jwtService;
    }

    /**
     * Ingests high-frequency live GPS updates from worker during travel.
     * Rejects updates when session is ARRIVED or STOPPED.
     */
    @PostMapping({"/partner/location", "/update-location"})
    public ResponseEntity<LocationUpdateResult> updateLiveLocation(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @Valid @RequestBody LocationUpdateRequest request) {

        UUID workerUserId = resolveUserId(authHeader, headerUserId, queryUserId);
        UUID bookingId = null;

        if (request.getBookingId() != null && !request.getBookingId().isBlank()) {
            try {
                bookingId = UUID.fromString(request.getBookingId().trim());
            } catch (Exception ignored) {}
        }

        if (bookingId == null) {
            return ResponseEntity.badRequest().body(LocationUpdateResult.builder()
                    .success(false)
                    .status("ERROR")
                    .trackingActive(false)
                    .message("bookingId is required")
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
     */
    @GetMapping("/latest/{bookingId}")
    public ResponseEntity<TrackingLocationResponse> getLatestLocation(
            @PathVariable("bookingId") UUID bookingId) {
        return ResponseEntity.ok(trackingService.getLatestLocation(bookingId));
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
     * Returns the driving road route between coordinates.
     */
    @GetMapping("/route")
    public ResponseEntity<TrackingRouteResponse> getRoute(
            @RequestParam("startLat") double startLat,
            @RequestParam("startLng") double startLng,
            @RequestParam("destLat") double destLat,
            @RequestParam("destLng") double destLng) {
        return ResponseEntity.ok(trackingService.getDrivingRoute(startLat, startLng, destLat, destLng));
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
        if (queryUserId != null) return queryUserId;
        if (headerUserId != null && !headerUserId.isBlank()) {
            try { return UUID.fromString(headerUserId.trim()); } catch (Exception ignored) {}
        }
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                UUID sub = jwtService.extractUserId(token);
                if (sub != null) return sub;
            } catch (Exception ignored) {}
        }
        return null;
    }
}
