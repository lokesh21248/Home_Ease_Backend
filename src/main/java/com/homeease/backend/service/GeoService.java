package com.homeease.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.*;

@Service
public class GeoService {

    private static final Logger logger = LoggerFactory.getLogger(GeoService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${homeease.locationiq.api-key:}")
    private String locationIqApiKey;

    @Value("${homeease.openrouteservice.api-key:}")
    private String openRouteServiceApiKey;

    public GeoService(ObjectMapper objectMapper) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
    }

    private String getEffectiveLocationIqKey() {
        if (locationIqApiKey != null && !locationIqApiKey.isBlank()) {
            return locationIqApiKey.trim();
        }
        String env = System.getenv("LOCATIONIQ_API_KEY");
        return env != null ? env.trim() : "";
    }

    private String getEffectiveOrsKey() {
        if (openRouteServiceApiKey != null && !openRouteServiceApiKey.isBlank()) {
            return openRouteServiceApiKey.trim();
        }
        String env = System.getenv("OPENROUTESERVICE_API_KEY");
        return env != null ? env.trim() : "";
    }

    /**
     * Autocomplete / Forward Geocoding using LocationIQ
     */
    public List<Map<String, Object>> searchLocations(String query, int limit) {
        String key = getEffectiveLocationIqKey();
        if (key.isBlank() || query == null || query.trim().length() < 2) {
            logger.warn("LocationIQ search skipped: empty query or missing API key");
            return Collections.emptyList();
        }

        try {
            URI uri = UriComponentsBuilder.fromHttpUrl("https://api.locationiq.com/v1/autocomplete")
                    .queryParam("key", key)
                    .queryParam("q", query.trim())
                    .queryParam("limit", Math.min(Math.max(1, limit), 10))
                    .queryParam("countrycodes", "in")
                    .queryParam("format", "json")
                    .build()
                    .toUri();

            ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode arrayNode = objectMapper.readTree(response.getBody());
                List<Map<String, Object>> results = new ArrayList<>();

                if (arrayNode.isArray()) {
                    for (JsonNode item : arrayNode) {
                        Map<String, Object> map = new HashMap<>();
                        map.put("placeId", item.path("place_id").asText(""));
                        map.put("displayName", item.path("display_name").asText(""));
                        map.put("lat", item.path("lat").asDouble());
                        map.put("lon", item.path("lon").asDouble());
                        map.put("type", item.path("type").asText(""));

                        JsonNode addr = item.path("address");
                        if (!addr.isMissingNode()) {
                            Map<String, Object> addrMap = new HashMap<>();
                            addrMap.put("road", addr.path("road").asText(""));
                            addrMap.put("suburb", addr.path("suburb").asText(""));
                            addrMap.put("city", addr.path("city").asText(addr.path("county").asText("")));
                            addrMap.put("state", addr.path("state").asText(""));
                            addrMap.put("postcode", addr.path("postcode").asText(""));
                            map.put("address", addrMap);
                        }
                        results.add(map);
                    }
                }
                return results;
            }
        } catch (Exception e) {
            logger.warn("LocationIQ search request failed: {}", e.getMessage());
        }

        return Collections.emptyList();
    }

    /**
     * Reverse Geocoding using LocationIQ
     */
    public Map<String, Object> reverseGeocode(double lat, double lon) {
        String key = getEffectiveLocationIqKey();
        if (key.isBlank()) {
            logger.warn("LocationIQ reverse geocoding skipped: missing API key");
            return Collections.emptyMap();
        }

        try {
            URI uri = UriComponentsBuilder.fromHttpUrl("https://us1.locationiq.com/v1/reverse")
                    .queryParam("key", key)
                    .queryParam("lat", lat)
                    .queryParam("lon", lon)
                    .queryParam("format", "json")
                    .build()
                    .toUri();

            ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode node = objectMapper.readTree(response.getBody());
                Map<String, Object> map = new HashMap<>();
                map.put("placeId", node.path("place_id").asText(""));
                map.put("displayName", node.path("display_name").asText(""));
                map.put("lat", node.path("lat").asDouble());
                map.put("lon", node.path("lon").asDouble());

                JsonNode addr = node.path("address");
                if (!addr.isMissingNode()) {
                    Map<String, Object> addrMap = new HashMap<>();
                    addrMap.put("road", addr.path("road").asText(""));
                    addrMap.put("houseNumber", addr.path("house_number").asText(""));
                    addrMap.put("suburb", addr.path("suburb").asText(""));
                    addrMap.put("city", addr.path("city").asText(addr.path("county").asText("")));
                    addrMap.put("state", addr.path("state").asText(""));
                    addrMap.put("postcode", addr.path("postcode").asText(""));
                    addrMap.put("country", addr.path("country").asText(""));
                    map.put("address", addrMap);
                }
                return map;
            }
        } catch (Exception e) {
            logger.warn("LocationIQ reverse request failed: {}", e.getMessage());
        }

        return Collections.emptyMap();
    }

    /**
     * Road Routing using OpenRouteService (HeiGIT API)
     * Endpoint: https://api.heigit.org/openrouteservice/v2/directions/driving-car/geojson
     */
    public Map<String, Object> getDirections(double startLat, double startLng, double destLat, double destLng) {
        String key = getEffectiveOrsKey();
        if (key.isBlank()) {
            logger.warn("OpenRouteService routing skipped: missing API key");
            return Collections.emptyMap();
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

                    List<List<Double>> coordinateList = new ArrayList<>();
                    List<List<Double>> leafletCoordinates = new ArrayList<>();
                    JsonNode coordinates = geometry.path("coordinates");
                    if (coordinates.isArray()) {
                        for (JsonNode pt : coordinates) {
                            if (pt.isArray() && pt.size() >= 2) {
                                double ptLng = pt.get(0).asDouble();
                                double ptLat = pt.get(1).asDouble();
                                coordinateList.add(Arrays.asList(ptLng, ptLat));
                                leafletCoordinates.add(Arrays.asList(ptLat, ptLng));
                            }
                        }
                    }

                    Map<String, Object> result = new HashMap<>();
                    result.put("provider", "openrouteservice");
                    result.put("found", true);
                    result.put("coordinates", coordinateList); // GeoJSON [lng, lat]
                    result.put("leafletCoordinates", leafletCoordinates); // Leaflet [lat, lng]
                    result.put("distanceMeters", Math.round(distanceMeters));
                    result.put("durationSeconds", Math.round(durationSeconds));
                    result.put("distanceText", formatDistance(distanceMeters));
                    result.put("durationText", formatDuration(durationSeconds));
                    result.put("geojson", root);

                    logger.info("OpenRouteService driving route computed: {} m, {} s, {} points",
                            Math.round(distanceMeters), Math.round(durationSeconds), coordinateList.size());
                    return result;
                }
            }
        } catch (Exception e) {
            logger.warn("OpenRouteService request failed: {}", e.getMessage());
        }

        return Collections.emptyMap();
    }

    private String formatDistance(double meters) {
        if (meters < 1000) {
            return Math.round(meters) + " m";
        }
        return String.format(Locale.US, "%.1f km", meters / 1000.0);
    }

    private String formatDuration(double seconds) {
        int mins = Math.max(1, (int) Math.round(seconds / 60.0));
        if (mins < 60) {
            return mins + " min" + (mins == 1 ? "" : "s");
        }
        int hrs = mins / 60;
        int remMins = mins % 60;
        return hrs + "h " + remMins + "m";
    }
}

