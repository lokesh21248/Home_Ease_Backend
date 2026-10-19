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

    @Value("${homeease.google.maps.api-key:}")
    private String googleMapsApiKey;

    @Value("${homeease.locationiq.api-key:}")
    private String locationIqApiKey;

    @Value("${homeease.openrouteservice.api-key:}")
    private String openRouteServiceApiKey;

    public GeoService(ObjectMapper objectMapper) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
    }

    private String getEffectiveGoogleMapsKey() {
        if (googleMapsApiKey != null && !googleMapsApiKey.isBlank()) {
            return googleMapsApiKey.trim();
        }
        String env = System.getenv("GOOGLE_MAPS_API_KEY");
        return env != null ? env.trim() : "";
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
     * Autocomplete / Forward Geocoding using Google Maps (fallback to LocationIQ)
     */
    public List<Map<String, Object>> searchLocations(String query, int limit) {
        if (query == null || query.trim().length() < 2) {
            return Collections.emptyList();
        }

        String googleKey = getEffectiveGoogleMapsKey();
        if (!googleKey.isBlank()) {
            List<Map<String, Object>> googleResults = searchGoogleGeocode(query.trim(), limit, googleKey);
            if (!googleResults.isEmpty()) {
                return googleResults;
            }
        }

        // Fallback to LocationIQ
        return searchLocationIq(query.trim(), limit);
    }

    private List<Map<String, Object>> searchGoogleGeocode(String query, int limit, String key) {
        try {
            URI uri = UriComponentsBuilder.fromHttpUrl("https://maps.googleapis.com/maps/api/geocode/json")
                    .queryParam("address", query)
                    .queryParam("components", "country:in")
                    .queryParam("key", key)
                    .build()
                    .toUri();

            ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                String status = root.path("status").asText("");
                if ("OK".equalsIgnoreCase(status)) {
                    JsonNode resultsNode = root.path("results");
                    List<Map<String, Object>> list = new ArrayList<>();
                    int max = Math.min(Math.max(1, limit), resultsNode.size());
                    for (int i = 0; i < max; i++) {
                        JsonNode item = resultsNode.get(i);
                        Map<String, Object> map = new HashMap<>();
                        map.put("placeId", item.path("place_id").asText(""));
                        map.put("displayName", item.path("formatted_address").asText(""));
                        map.put("lat", item.path("geometry").path("location").path("lat").asDouble());
                        map.put("lon", item.path("geometry").path("location").path("lng").asDouble());
                        map.put("type", item.path("types").isArray() && item.path("types").size() > 0
                                ? item.path("types").get(0).asText("") : "street_address");

                        Map<String, Object> addrMap = parseGoogleAddressComponents(item.path("address_components"));
                        map.put("address", addrMap);
                        list.add(map);
                    }
                    return list;
                }
            }
        } catch (Exception e) {
            logger.warn("Google Geocode search failed, trying fallback: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    private List<Map<String, Object>> searchLocationIq(String query, int limit) {
        String key = getEffectiveLocationIqKey();
        if (key.isBlank()) {
            return Collections.emptyList();
        }

        try {
            URI uri = UriComponentsBuilder.fromHttpUrl("https://api.locationiq.com/v1/autocomplete")
                    .queryParam("key", key)
                    .queryParam("q", query)
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
     * Reverse Geocoding using Google Maps (fallback to LocationIQ)
     */
    public Map<String, Object> reverseGeocode(double lat, double lon) {
        String googleKey = getEffectiveGoogleMapsKey();
        if (!googleKey.isBlank()) {
            Map<String, Object> googleRes = reverseGeocodeGoogle(lat, lon, googleKey);
            if (!googleRes.isEmpty()) {
                return googleRes;
            }
        }

        // Fallback to LocationIQ
        return reverseGeocodeLocationIq(lat, lon);
    }

    private Map<String, Object> reverseGeocodeGoogle(double lat, double lon, String key) {
        try {
            URI uri = UriComponentsBuilder.fromHttpUrl("https://maps.googleapis.com/maps/api/geocode/json")
                    .queryParam("latlng", lat + "," + lon)
                    .queryParam("key", key)
                    .build()
                    .toUri();

            ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                if ("OK".equalsIgnoreCase(root.path("status").asText(""))) {
                    JsonNode firstResult = root.path("results").get(0);
                    if (firstResult != null) {
                        Map<String, Object> map = new HashMap<>();
                        map.put("placeId", firstResult.path("place_id").asText(""));
                        map.put("displayName", firstResult.path("formatted_address").asText(""));
                        map.put("lat", firstResult.path("geometry").path("location").path("lat").asDouble());
                        map.put("lon", firstResult.path("geometry").path("location").path("lng").asDouble());
                        map.put("address", parseGoogleAddressComponents(firstResult.path("address_components")));
                        return map;
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Google Reverse Geocoding failed, trying fallback: {}", e.getMessage());
        }
        return Collections.emptyMap();
    }

    private Map<String, Object> reverseGeocodeLocationIq(double lat, double lon) {
        String key = getEffectiveLocationIqKey();
        if (key.isBlank()) {
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
     * Road Routing using Google Directions API (fallback to OpenRouteService)
     */
    public Map<String, Object> getDirections(double startLat, double startLng, double destLat, double destLng) {
        String googleKey = getEffectiveGoogleMapsKey();
        if (!googleKey.isBlank()) {
            Map<String, Object> googleDirections = getGoogleDirections(startLat, startLng, destLat, destLng, googleKey);
            if (!googleDirections.isEmpty()) {
                return googleDirections;
            }
        }

        // Fallback to OpenRouteService
        return getOrsDirections(startLat, startLng, destLat, destLng);
    }

    private Map<String, Object> getGoogleDirections(double startLat, double startLng, double destLat, double destLng, String key) {
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
                        result.put("distanceText", leg.path("distance").path("text").asText(formatDistance(distanceMeters)));
                        result.put("durationText", leg.path("duration").path("text").asText(formatDuration(durationSeconds)));
                        result.put("polyline", encodedPolyline);
                        result.put("directions", root);

                        logger.info("Google Directions route computed: {} m, {} s, {} points",
                                Math.round(distanceMeters), Math.round(durationSeconds), geoJsonCoordinates.size());
                        return result;
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Google Directions API failed, trying fallback: {}", e.getMessage());
        }
        return Collections.emptyMap();
    }

    private Map<String, Object> getOrsDirections(double startLat, double startLng, double destLat, double destLng) {
        String key = getEffectiveOrsKey();
        if (key.isBlank()) {
            logger.warn("Routing skipped: no Google Maps or OpenRouteService key configured");
            return Collections.emptyMap();
        }

        try {
            String url = "https://api.heigit.org/openrouteservice/v2/directions/driving-car/geojson";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", key);
            headers.set("Accept", "application/json, application/geo+json");

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
                    result.put("coordinates", coordinateList);
                    result.put("leafletCoordinates", leafletCoordinates);
                    result.put("distanceMeters", Math.round(distanceMeters));
                    result.put("durationSeconds", Math.round(durationSeconds));
                    result.put("distanceText", formatDistance(distanceMeters));
                    result.put("durationText", formatDuration(durationSeconds));
                    result.put("geojson", root);

                    return result;
                }
            }
        } catch (Exception e) {
            logger.warn("OpenRouteService request failed: {}", e.getMessage());
        }

        return Collections.emptyMap();
    }

    /**
     * Decodes Google Maps Encoded Polyline algorithm into [lat, lng] pairs
     */
    public static List<List<Double>> decodePolyline(String encoded) {
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

    private Map<String, Object> parseGoogleAddressComponents(JsonNode components) {
        Map<String, Object> addrMap = new HashMap<>();
        if (components == null || !components.isArray()) {
            return addrMap;
        }

        for (JsonNode comp : components) {
            JsonNode types = comp.path("types");
            String longName = comp.path("long_name").asText("");
            for (JsonNode t : types) {
                String typeStr = t.asText("");
                switch (typeStr) {
                    case "route":
                        addrMap.put("road", longName);
                        break;
                    case "street_number":
                        addrMap.put("houseNumber", longName);
                        break;
                    case "sublocality_level_1":
                    case "sublocality":
                    case "neighborhood":
                        if (!addrMap.containsKey("suburb")) {
                            addrMap.put("suburb", longName);
                        }
                        break;
                    case "locality":
                        addrMap.put("city", longName);
                        break;
                    case "administrative_area_level_1":
                        addrMap.put("state", longName);
                        break;
                    case "postal_code":
                        addrMap.put("postcode", longName);
                        break;
                    case "country":
                        addrMap.put("country", longName);
                        break;
                }
            }
        }
        return addrMap;
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
