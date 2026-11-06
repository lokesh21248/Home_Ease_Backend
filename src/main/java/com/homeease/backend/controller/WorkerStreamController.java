package com.homeease.backend.controller;

import com.homeease.backend.exception.ResourceNotFoundException;
import com.homeease.backend.security.JwtService;
import com.homeease.backend.service.InstantAcceptService;
import com.homeease.backend.service.InstantAcceptService.AcceptBookingResult;
import com.homeease.backend.service.WorkerStreamService;
import lombok.Data;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping({"/api/v1/workers/stream", "/api/workers/stream"})
@CrossOrigin(origins = "*")
public class WorkerStreamController {

    private final WorkerStreamService workerStreamService;
    private final InstantAcceptService instantAcceptService;
    private final JwtService jwtService;

    public WorkerStreamController(WorkerStreamService workerStreamService,
                                  InstantAcceptService instantAcceptService,
                                  JwtService jwtService) {
        this.workerStreamService = workerStreamService;
        this.instantAcceptService = instantAcceptService;
        this.jwtService = jwtService;
    }

    @Data
    public static class WorkerPingRequest {
        private UUID workerId;
        private double lat;
        private double lng;
        private String zone;
        private Double accuracy;
    }

    /**
     * Real-time worker telemetry ping endpoint:
     * Updates Redis GEO set geo:workers:{zone} and sets 30-second TTL heartbeat.
     * POST /api/v1/workers/stream/ping
     */
    @PostMapping("/ping")
    public ResponseEntity<Map<String, Object>> recordPing(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody WorkerPingRequest request) {

        UUID workerId = request.getWorkerId();
        if (workerId == null && headerUserId != null && !headerUserId.isBlank()) {
            try {
                workerId = UUID.fromString(headerUserId.trim());
            } catch (Exception ignored) {}
        }
        if (workerId == null && authHeader != null && !authHeader.isBlank()) {
            workerId = jwtService.extractUserId(authHeader);
        }

        if (workerId == null) {
            throw new ResourceNotFoundException("Worker ID required in ping request.");
        }

        workerStreamService.recordWorkerPing(workerId, request.getLat(), request.getLng(), request.getZone(), request.getAccuracy());

        return ResponseEntity.ok(Map.of(
                "status", "PONG",
                "workerId", workerId.toString(),
                "ttlSeconds", 30,
                "timestamp", System.currentTimeMillis()
        ));
    }

    /**
     * Atomic job acceptance with race condition protection:
     * When 20 workers accept simultaneously, exactly 1 wins (HTTP 200) and 19 get HTTP 409 Conflict ("job taken").
     * POST /api/v1/workers/stream/bookings/{id}/accept
     */
    @PostMapping("/bookings/{id}/accept")
    public ResponseEntity<AcceptBookingResult> acceptJob(
            @PathVariable("id") UUID bookingId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "workerUserId", required = false) UUID queryWorkerUserId) {

        UUID workerUserId = queryWorkerUserId;
        if (workerUserId == null && headerUserId != null && !headerUserId.isBlank()) {
            try {
                workerUserId = UUID.fromString(headerUserId.trim());
            } catch (Exception ignored) {}
        }
        if (workerUserId == null && authHeader != null && !authHeader.isBlank()) {
            workerUserId = jwtService.extractUserId(authHeader);
        }

        if (workerUserId == null) {
            throw new ResourceNotFoundException("Worker user ID required to accept booking.");
        }

        AcceptBookingResult result = instantAcceptService.acceptBooking(bookingId, workerUserId);
        return ResponseEntity.ok(result);
    }
}

