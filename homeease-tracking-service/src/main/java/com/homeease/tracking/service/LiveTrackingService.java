package com.homeease.tracking.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.tracking.dto.LocationPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.*;

@Service
public class LiveTrackingService {

    private static final Logger logger = LoggerFactory.getLogger(LiveTrackingService.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final RestTemplate restTemplate;

    @Value("${homeease.google.maps.api-key:}")
    private String googleMapsApiKey;

    @Value("${homeease.openrouteservice.api-key:}")
    private String orsApiKey;

    // Cache TTL: 2 hours for in-transit tracking data
    private static final Duration TRACKING_TTL = Duration.ofHours(2);
    private static final String GEO_WORKERS_KEY = "active_workers_geo";

    public LiveTrackingService(StringRedisTemplate redisTemplate,
                               ObjectMapper objectMapper,
                               SimpMessagingTemplate messagingTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.messagingTemplate = messagingTemplate;
        this.restTemplate = new RestTemplate();
    }

    private String getEffectiveGoogleMapsKey() {
        if (googleMapsApiKey != null && !googleMapsApiKey.isBlank()) {
            return googleMapsApiKey.trim();
        }
        String envKey = System.getenv("GOOGLE_MAPS_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            return envKey.trim();
        }
        return "";
    }

    private String getEffectiveOrsKey() {
        if (orsApiKey != null && !orsApiKey.isBlank()) {
            return orsApiKey.trim();
        }
        String envKey = System.getenv("OPENROUTESERVICE_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            return envKey.trim();
        }
        return "";
    }

    /**
     * Backward-compatible overload for existing calls.
     */
    public LocationPayload processLocationUpdate(String bookingId, LocationPayload payload) {
        return processLocationUpdate(bookingId, payload, null);
    }

    /**
     * Records partner live coordinates in Redis (Key-Value + Redis GEO)
     * and broadcasts live to customer WebSocket subscribers.
     * Validates:
     * 1. Coordinate ranges (-90 to +90 lat, -180 to +180 lng, non-NaN)
     * 2. Assignment & booking state in Redis (rejects if completed/cancelled or unauthorized)
     * 3. Timestamp ordering (rejects stale updates)
     */
    public LocationPayload processLocationUpdate(String bookingId, LocationPayload payload, String authenticatedUserId) {
        if (payload == null) {
            throw new IllegalArgumentException("Payload cannot be null");
        }

        if (bookingId == null || bookingId.isBlank()) {
            throw new IllegalArgumentException("bookingId cannot be blank");
        }

        payload.setBookingId(bookingId);

        // 1. Validate coordinates
        Double lat = payload.getLat();
        Double lng = payload.getLng();
        if (lat == null || lng == null ||
                Double.isNaN(lat) || Double.isInfinite(lat) ||
                Double.isNaN(lng) || Double.isInfinite(lng) ||
                lat < -90.0 || lat > 90.0 ||
                lng < -180.0 || lng > 180.0) {
            throw new IllegalArgumentException(String.format(
                    "Invalid GPS coordinates: lat=%s, lng=%s (Must be between -90..90 and -180..180)", lat, lng));
        }

        // 2. Validate booking assignment & status in Redis if assignment metadata exists
        String assignmentKey = "tracking:assignment:" + bookingId;
        try {
            String assignmentJson = redisTemplate.opsForValue().get(assignmentKey);
            if (assignmentJson != null && !assignmentJson.isBlank()) {
                JsonNode assignmentNode = objectMapper.readTree(assignmentJson);
                String bookingStatus = assignmentNode.path("status").asText("");
                if ("COMPLETED".equalsIgnoreCase(bookingStatus) || "CANCELLED".equalsIgnoreCase(bookingStatus)) {
                    throw new IllegalStateException("Live tracking has ended: booking " + bookingId + " is " + bookingStatus);
                }

                // If authenticated worker provided, verify against assigned worker
                if (authenticatedUserId != null && !authenticatedUserId.isBlank()) {
                    String assignedUserId = assignmentNode.path("userId").asText("");
                    String assignedWorkerId = assignmentNode.path("workerId").asText("");
                    if (!assignedUserId.isBlank() && !assignedUserId.equalsIgnoreCase(authenticatedUserId) &&
                        !assignedWorkerId.isBlank() && !assignedWorkerId.equalsIgnoreCase(authenticatedUserId)) {
                        throw new SecurityException("Authenticated user " + authenticatedUserId + " is not assigned to booking " + bookingId);
                    }
                }
            }
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            throw e;
        } catch (Exception e) {
            logger.warn("Could not check assignment cache for booking {}: {}", bookingId, e.getMessage());
        }

        // 3. Stale update protection
        if (payload.getTimestamp() == null) {
            payload.setTimestamp(System.currentTimeMillis());
        }

        LocationPayload latestExisting = getLatestLocation(bookingId);
        if (latestExisting != null && latestExisting.getTimestamp() != null) {
            if (payload.getTimestamp() < latestExisting.getTimestamp()) {
                logger.warn("Stale GPS update rejected for booking {}: incoming {} < latest cached {}",
                        bookingId, payload.getTimestamp(), latestExisting.getTimestamp());
                return latestExisting;
            }
        }

        payload.setTrackingActive(true);
        payload.setStatus("TRACKING");

        try {
            // 4. Serialize full location details into Redis Key with 2-hour TTL
            String json = objectMapper.writeValueAsString(payload);
            String trackingKey = "tracking:booking:" + bookingId;
            redisTemplate.opsForValue().set(trackingKey, json, TRACKING_TTL);

            // 5. Update Redis GEO spatial index for active workers (longitude, latitude)
            String effectiveWorkerId = (authenticatedUserId != null && !authenticatedUserId.isBlank())
                    ? authenticatedUserId
                    : payload.getEffectiveId();
            redisTemplate.opsForGeo().add(GEO_WORKERS_KEY, new Point(payload.getLng(), payload.getLat()), effectiveWorkerId);

            // 6. Push to WebSocket Topics: /topic/tracking/{bookingId} & /topic/track/{bookingId}
            messagingTemplate.convertAndSend("/topic/tracking/" + bookingId, payload);
            messagingTemplate.convertAndSend("/topic/track/" + bookingId, payload);

            logger.info("Broadcasted live GPS for booking {} to /topic/tracking/{}: [{}, {}], bearing={}",
                    bookingId, bookingId, payload.getLat(), payload.getLng(), payload.getBearing());

        } catch (Exception e) {
            logger.warn("Failed to process live tracking update for booking {}: {}", bookingId, e.getMessage());
        }

        return payload;
    }

    /**
     * Retrieves the latest known location for a booking.
     * Handles both full JSON payloads and legacy comma-delimited strings ("lat,lng").
     */
    public LocationPayload getLatestLocation(String bookingId) {
        if (bookingId == null || bookingId.isBlank()) {
            return null;
        }

        try {
            String trackingKey = "tracking:booking:" + bookingId;
            String raw = redisTemplate.opsForValue().get(trackingKey);
            if (raw != null && !raw.isBlank()) {
                String trimmed = raw.trim();
                if (trimmed.startsWith("{")) {
                    return objectMapper.readValue(trimmed, LocationPayload.class);
                } else if (trimmed.contains(",")) {
                    // Legacy fallback format "lat,lng"
                    String[] parts = trimmed.split(",");
                    if (parts.length >= 2) {
                        return LocationPayload.builder()
                                .bookingId(bookingId)
                                .lat(Double.parseDouble(parts[0].trim()))
                                .lng(Double.parseDouble(parts[1].trim()))
                                .timestamp(System.currentTimeMillis())
                                .trackingActive(true)
                                .status("TRACKING")
                                .build();
                    }
                }
            }

            // Check if booking was marked completed or cancelled
            String assignmentKey = "tracking:assignment:" + bookingId;
            String assignmentJson = redisTemplate.opsForValue().get(assignmentKey);
            if (assignmentJson != null && !assignmentJson.isBlank()) {
                JsonNode assignmentNode = objectMapper.readTree(assignmentJson);
                String status = assignmentNode.path("status").asText("");
                if ("COMPLETED".equalsIgnoreCase(status) || "CANCELLED".equalsIgnoreCase(status)) {
                    return LocationPayload.builder()
                            .bookingId(bookingId)
                            .trackingActive(false)
                            .status(status)
                            .message("Tracking ended: booking status is " + status)
                            .build();
                }
            }
        } catch (Exception e) {
            logger.error("Error reading live location from Redis for booking {}: {}", bookingId, e.getMessage());
        }
        return null;
    }

    /**
     * Calculates distance (Haversine formula in KM) and estimated time of arrival (ETA in minutes).
     */
    public Map<String, Object> calculateDistanceAndEta(String bookingId, double customerLat, double customerLng) {
        Map<String, Object> response = new HashMap<>();
        LocationPayload workerLoc = getLatestLocation(bookingId);

        if (workerLoc == null || workerLoc.getLat() == null || workerLoc.getLng() == null) {
            response.put("found", false);
            response.put("bookingId", bookingId);
            response.put("message", "Worker live location not yet available");
            return response;
        }

        if (Boolean.FALSE.equals(workerLoc.getTrackingActive())) {
            response.put("found", false);
            response.put("bookingId", bookingId);
            response.put("status", workerLoc.getStatus());
            response.put("message", "Booking tracking is closed (" + workerLoc.getStatus() + ")");
            return response;
        }

        double distanceKm = haversine(workerLoc.getLat(), workerLoc.getLng(), customerLat, customerLng);
        // Estimate average urban speed: 25 km/h -> 2.4 min per km + 2 min arrival buffer
        int etaMinutes = Math.max(1, (int) Math.round((distanceKm / 25.0) * 60.0) + 2);

        response.put("found", true);
        response.put("bookingId", bookingId);
        response.put("workerId", workerLoc.getWorkerId());
        response.put("workerLat", workerLoc.getLat());
        response.put("workerLng", workerLoc.getLng());
        response.put("bearing", workerLoc.getBearing() != null ? workerLoc.getBearing() : 0.0);
        response.put("customerLat", customerLat);
        response.put("customerLng", customerLng);
        response.put("distanceKm", Math.round(distanceKm * 100.0) / 100.0);
        response.put("etaMinutes", etaMinutes);
        response.put("lastUpdated", workerLoc.getTimestamp());
        response.put("trackingActive", true);

        return response;
    }

    /**
     * Computes driving route using OpenRouteService (HeiGIT API)
     * POST https://api.heigit.org/openrouteservice/v2/directions/driving-car/geojson
     *
     * Coordinate convention:
     * - OpenRouteService API expects [longitude, latitude]
     * - Leaflet markers/polylines expect [latitude, longitude]
     * This method returns BOTH: 'coordinates' as [lng, lat] (GeoJSON standard)
     * and 'leafletCoordinates' as [lat, lng] (Leaflet standard).
     */
    public Map<String, Object> getDrivingRoute(double startLat, double startLng, double destLat, double destLng) {
        // Validate coordinates
        if (Double.isNaN(startLat) || Double.isNaN(startLng) || Double.isNaN(destLat) || Double.isNaN(destLng) ||
            startLat < -90.0 || startLat > 90.0 || destLat < -90.0 || destLat > 90.0 ||
            startLng < -180.0 || startLng > 180.0 || destLng < -180.0 || destLng > 180.0) {
            return Map.of(
                    "found", false,
                    "provider", "openrouteservice",
                    "message", "Invalid coordinate bounds provided for routing"
            );
        }

        String googleKey = getEffectiveGoogleMapsKey();
        if (!googleKey.isBlank()) {
            Map<String, Object> googleRes = getGoogleRoadRoute(startLat, startLng, destLat, destLng, googleKey);
            if (googleRes != null && Boolean.TRUE.equals(googleRes.get("found"))) {
                return googleRes;
            }
        }

        String key = getEffectiveOrsKey();
        if (key.isBlank()) {
            logger.warn("Road routing skipped: Neither GOOGLE_MAPS_API_KEY nor OPENROUTESERVICE_API_KEY is configured");
            return Map.of("found", false, "provider", "none", "message", "Map routing API keys not configured");
        }

        try {
            String url = "https://api.heigit.org/openrouteservice/v2/directions/driving-car/geojson";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", key);
            headers.set("Accept", "application/json, application/geo+json");

            // OpenRouteService coordinates format: [[startLng, startLat], [destLng, destLat]]
            Map<String, Object> requestBody = new HashMap<>();
            List<List<Double>> coords = new ArrayList<>();
            coords.add(Arrays.asList(startLng, startLat));
            coords.add(Arrays.asList(destLng, destLat));
            requestBody.put("coordinates", coords);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode features = root.path("features");

                if (features.isArray() && features.size() > 0) {
                    JsonNode firstFeature = features.get(0);
                    JsonNode geometry = firstFeature.path("geometry");
                    JsonNode summary = firstFeature.path("properties").path("summary");

                    double distanceMeters = summary.path("distance").asDouble(0.0);
                    double durationSeconds = summary.path("duration").asDouble(0.0);

                    List<List<Double>> geoJsonCoordinates = new ArrayList<>();
                    List<List<Double>> leafletCoordinates = new ArrayList<>();

                    JsonNode coordinates = geometry.path("coordinates");
                    if (coordinates.isArray()) {
                        for (JsonNode pt : coordinates) {
                            if (pt.isArray() && pt.size() >= 2) {
                                double ptLng = pt.get(0).asDouble();
                                double ptLat = pt.get(1).asDouble();
                                geoJsonCoordinates.add(Arrays.asList(ptLng, ptLat)); // [lng, lat]
                                leafletCoordinates.add(Arrays.asList(ptLat, ptLng)); // [lat, lng]
                            }
                        }
                    }

                    Map<String, Object> result = new HashMap<>();
                    result.put("provider", "openrouteservice");
                    result.put("found", true);
                    result.put("coordinates", geoJsonCoordinates);       // GeoJSON standard: [lng, lat]
                    result.put("leafletCoordinates", leafletCoordinates); // Leaflet standard: [lat, lng]
                    result.put("distanceMeters", Math.round(distanceMeters));
                    result.put("durationSeconds", Math.round(durationSeconds));
                    result.put("distanceText", distanceMeters < 1000
                            ? Math.round(distanceMeters) + " m"
                            : String.format(Locale.US, "%.1f km", distanceMeters / 1000.0));
                    int mins = Math.max(1, (int) Math.round(durationSeconds / 60.0));
                    result.put("durationText", mins < 60
                            ? mins + " min" + (mins == 1 ? "" : "s")
                            : (mins / 60) + "h " + (mins % 60) + "m");
                    result.put("geojson", root);

                    logger.info("OpenRouteService route computed via tracking service: {} m, {} s, {} points",
                            Math.round(distanceMeters), Math.round(durationSeconds), geoJsonCoordinates.size());
                    return result;
                }
            }
        } catch (Exception e) {
            logger.warn("OpenRouteService request failed in tracking service: {}", e.getMessage());
            return Map.of(
                    "found", false,
                    "provider", "openrouteservice",
                    "message", "Route calculation failed: " + e.getMessage()
            );
        }

        return Map.of("found", false, "provider", "openrouteservice", "message", "No road route found for specified coordinates");
    }

    private Map<String, Object> getGoogleRoadRoute(double startLat, double startLng, double destLat, double destLng, String key) {
        try {
            URI uri = UriComponentsBuilder.fromHttpUrl("https://maps.googleapis.com/maps/api/directions/json")
                    .queryParam("origin", startLat + "," + startLng)
                    .queryParam("destination", destLat + "," + destLng)
                    .queryParam("mode", "driving")
                    .queryParam("key", key)
                    .build()
                    .toUri();

            ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                String status = root.path("status").asText("");
                if ("OK".equalsIgnoreCase(status)) {
                    JsonNode routes = root.path("routes");
                    if (routes.isArray() && routes.size() > 0) {
                        JsonNode firstRoute = routes.get(0);
                        JsonNode leg = firstRoute.path("legs").get(0);

                        double distanceMeters = leg.path("distance").path("value").asDouble(0.0);
                        double durationSeconds = leg.path("duration").path("value").asDouble(0.0);
                        String encodedPolyline = firstRoute.path("overview_polyline").path("points").asText("");

                        List<List<Double>> leafletCoordinates = decodePolyline(encodedPolyline);
                        List<List<Double>> geoJsonCoordinates = new ArrayList<>();
                        for (List<Double> pt : leafletCoordinates) {
                            geoJsonCoordinates.add(Arrays.asList(pt.get(1), pt.get(0))); // [lng, lat]
                        }

                        Map<String, Object> result = new HashMap<>();
                        result.put("provider", "google");
                        result.put("found", true);
                        result.put("coordinates", geoJsonCoordinates);       // [lng, lat]
                        result.put("leafletCoordinates", leafletCoordinates); // [lat, lng]
                        result.put("distanceMeters", Math.round(distanceMeters));
                        result.put("durationSeconds", Math.round(durationSeconds));
                        result.put("distanceText", leg.path("distance").path("text").asText(distanceMeters < 1000
                                ? Math.round(distanceMeters) + " m"
                                : String.format(Locale.US, "%.1f km", distanceMeters / 1000.0)));
                        int mins = Math.max(1, (int) Math.round(durationSeconds / 60.0));
                        result.put("durationText", leg.path("duration").path("text").asText(mins < 60
                                ? mins + " min" + (mins == 1 ? "" : "s")
                                : (mins / 60) + "h " + (mins % 60) + "m"));
                        result.put("polyline", encodedPolyline);
                        result.put("directions", root);

                        logger.info("Google Directions route computed: {} m, {} s, {} points",
                                Math.round(distanceMeters), Math.round(durationSeconds), geoJsonCoordinates.size());
                        return result;
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Google Directions API failed in tracking service, trying fallback: {}", e.getMessage());
        }
        return Collections.emptyMap();
    }

    private static List<List<Double>> decodePolyline(String encoded) {
        List<List<Double>> poly = new ArrayList<>();
        if (encoded == null || encoded.isBlank()) {
            return poly;
        }

        int index = 0, len = encoded.length();
        int lat = 0, lng = 0;

        while (index < len) {
            int b, shift = 0, result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            int dlat = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            lat += dlat;

            shift = 0;
            result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            int dlng = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            lng += dlng;

            double latitude = (double) lat / 1E5;
            double longitude = (double) lng / 1E5;
            poly.add(Arrays.asList(latitude, longitude));
        }
        return poly;
    }

    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371; // Earth radius in km
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}
