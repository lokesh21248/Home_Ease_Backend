package com.homeease.backend.controller;

import com.homeease.backend.service.GeoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/v1/geo", "/api/geo"})
@CrossOrigin(origins = "*")
public class GeoController {

    private final GeoService geoService;

    public GeoController(GeoService geoService) {
        this.geoService = geoService;
    }

    /**
     * Address Search / Autocomplete via Google Maps (Places API New / Geocoding API)
     * GET /api/v1/geo/search?q=Kukatpally&limit=5
     */
    @GetMapping({"/search", "/autocomplete"})
    public ResponseEntity<List<Map<String, Object>>> search(
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "limit", defaultValue = "5") int limit) {
        if (query == null || query.trim().length() < 2) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        return ResponseEntity.ok(geoService.searchLocations(query.trim(), limit));
    }

    /**
     * Reverse Geocoding via Google Maps (Geocoding API)
     * GET /api/v1/geo/reverse?lat=17.4485&lng=78.3748
     */
    @GetMapping("/reverse")
    public ResponseEntity<Map<String, Object>> reverseGeocode(
            @RequestParam("lat") double lat,
            @RequestParam(value = "lng", required = false) Double lng,
            @RequestParam(value = "lon", required = false) Double lon) {
        double effectiveLng = (lng != null) ? lng : (lon != null ? lon : 0.0);
        if (Double.isNaN(lat) || Double.isNaN(effectiveLng) || lat < -90.0 || lat > 90.0 || effectiveLng < -180.0 || effectiveLng > 180.0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "found", false,
                    "message", "Invalid coordinates. Latitude must be between -90 and 90, longitude between -180 and 180."
            ));
        }
        Map<String, Object> result = geoService.reverseGeocode(lat, effectiveLng);
        if (result == null || result.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "found", false,
                    "message", "No address found for specified coordinates"
            ));
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Road Routing / Directions via Google Maps (Routes API v2 / Directions API)
     * GET /api/v1/geo/directions?startLat=17.4485&startLng=78.3748&destLat=17.4585&destLng=78.3848
     */
    @GetMapping({"/directions", "/route"})
    public ResponseEntity<Map<String, Object>> getDirections(
            @RequestParam("startLat") double startLat,
            @RequestParam("startLng") double startLng,
            @RequestParam("destLat") double destLat,
            @RequestParam("destLng") double destLng) {
        if (Double.isNaN(startLat) || Double.isNaN(startLng) || Double.isNaN(destLat) || Double.isNaN(destLng) ||
            startLat < -90.0 || startLat > 90.0 || destLat < -90.0 || destLat > 90.0 ||
            startLng < -180.0 || startLng > 180.0 || destLng < -180.0 || destLng > 180.0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "found", false,
                    "message", "Invalid coordinate bounds provided for routing. Latitude must be [-90, 90] and longitude [-180, 180]."
            ));
        }
        Map<String, Object> directions = geoService.getDirections(startLat, startLng, destLat, destLng);
        if (directions == null || directions.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "found", false,
                    "provider", "none",
                    "message", "No driving route available for specified coordinates",
                    "coordinates", List.of()
            ));
        }
        return ResponseEntity.ok(directions);
    }

    /**
     * Road Routing POST endpoint
     */
    @PostMapping({"/directions", "/route"})
    public ResponseEntity<Map<String, Object>> getDirectionsPost(@RequestBody Map<String, Object> body) {
        double startLat = ((Number) body.getOrDefault("startLat", 0.0)).doubleValue();
        double startLng = ((Number) body.getOrDefault("startLng", 0.0)).doubleValue();
        double destLat = ((Number) body.getOrDefault("destLat", 0.0)).doubleValue();
        double destLng = ((Number) body.getOrDefault("destLng", 0.0)).doubleValue();

        if (Double.isNaN(startLat) || Double.isNaN(startLng) || Double.isNaN(destLat) || Double.isNaN(destLng) ||
            startLat < -90.0 || startLat > 90.0 || destLat < -90.0 || destLat > 90.0 ||
            startLng < -180.0 || startLng > 180.0 || destLng < -180.0 || destLng > 180.0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "found", false,
                    "message", "Invalid coordinate bounds provided for routing. Latitude must be [-90, 90] and longitude [-180, 180]."
            ));
        }

        Map<String, Object> directions = geoService.getDirections(startLat, startLng, destLat, destLng);
        if (directions == null || directions.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "found", false,
                    "provider", "none",
                    "message", "No driving route available for specified coordinates",
                    "coordinates", List.of()
            ));
        }
        return ResponseEntity.ok(directions);
    }
}
