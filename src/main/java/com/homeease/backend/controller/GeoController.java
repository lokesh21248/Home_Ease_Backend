package com.homeease.backend.controller;

import com.homeease.backend.service.GeoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
     * Address Search / Autocomplete via LocationIQ
     * GET /api/v1/geo/search?q=Kukatpally&limit=5
     */
    @GetMapping({"/search", "/autocomplete"})
    public ResponseEntity<List<Map<String, Object>>> search(
            @RequestParam("q") String query,
            @RequestParam(value = "limit", defaultValue = "5") int limit) {
        return ResponseEntity.ok(geoService.searchLocations(query, limit));
    }

    /**
     * Reverse Geocoding via LocationIQ
     * GET /api/v1/geo/reverse?lat=17.4485&lng=78.3748
     */
    @GetMapping("/reverse")
    public ResponseEntity<Map<String, Object>> reverseGeocode(
            @RequestParam("lat") double lat,
            @RequestParam("lng") double lng) {
        return ResponseEntity.ok(geoService.reverseGeocode(lat, lng));
    }

    /**
     * Road Routing / Directions via OpenRouteService (HeiGIT API)
     * GET /api/v1/geo/directions?startLat=17.4485&startLng=78.3748&destLat=17.4585&destLng=78.3848
     */
    @GetMapping({"/directions", "/route"})
    public ResponseEntity<Map<String, Object>> getDirections(
            @RequestParam("startLat") double startLat,
            @RequestParam("startLng") double startLng,
            @RequestParam("destLat") double destLat,
            @RequestParam("destLng") double destLng) {
        Map<String, Object> directions = geoService.getDirections(startLat, startLng, destLat, destLng);
        if (directions == null || directions.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "found", false,
                    "provider", "openrouteservice",
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

        Map<String, Object> directions = geoService.getDirections(startLat, startLng, destLat, destLng);
        if (directions == null || directions.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "found", false,
                    "provider", "openrouteservice",
                    "message", "No driving route available for specified coordinates",
                    "coordinates", List.of()
            ));
        }
        return ResponseEntity.ok(directions);
    }
}
