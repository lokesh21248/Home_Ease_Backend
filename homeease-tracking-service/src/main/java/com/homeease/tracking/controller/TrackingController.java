package com.homeease.tracking.controller;

import com.homeease.tracking.dto.LocationPayload;
import com.homeease.tracking.service.LiveTrackingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/tracking")
@CrossOrigin(origins = "*")
public class TrackingController {

    private final LiveTrackingService trackingService;

    public TrackingController(LiveTrackingService trackingService) {
        this.trackingService = trackingService;
    }

    /**
     * REST Partner GPS Ingestion API (called by Partner App every 3-5 seconds):
     * POST /api/v1/tracking/partner/location or POST /api/v1/tracking/update-location
     */
    @PostMapping({"/partner/location", "/update-location"})
    public ResponseEntity<Map<String, Object>> updateLiveLocationREST(@RequestBody LocationPayload payload) {
        if (payload.getBookingId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "bookingId is required"));
        }
        LocationPayload saved = trackingService.processLocationUpdate(payload.getBookingId(), payload);
        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "message", "Live tracking location updated and broadcasted to WebSocket topic",
                "data", saved
        ));
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
}
