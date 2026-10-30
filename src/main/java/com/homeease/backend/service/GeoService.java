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

    private static class RouteCacheEntry {
        final Map<String, Object> data;
        final long expiresAt;

        RouteCacheEntry(Map<String, Object> data, long ttlMillis) {
            this.data = data;
            this.expiresAt = System.currentTimeMillis() + ttlMillis;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    private static class GeoCacheEntry {
        final Map<String, Object> data;
        final long expiresAt;

        GeoCacheEntry(Map<String, Object> data, long ttlMillis) {
            this.data = data;
            this.expiresAt = System.currentTimeMillis() + ttlMillis;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    private final Map<String, RouteCacheEntry> routeCache = Collections.synchronizedMap(
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, RouteCacheEntry> eldest) {
                    return size() > 100;
                }
            }
    );

    private final Map<String, GeoCacheEntry> reverseGeocodeCache = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, GeoCacheEntry> eldest) {
                    return size() > 250;
                }
            }
    );

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

    /**
     * Autocomplete / Forward Geocoding using Google Maps (Places API New or Geocoding)
     */
    public List<Map<String, Object>> searchLocations(String query, int limit) {
        if (query == null || query.trim().length() < 2) {
            return Collections.emptyList();
        }
        int effectiveLimit = Math.min(Math.max(1, limit), 10);
        String trimmedQuery = query.trim();

        String googleKey = getEffectiveGoogleMapsKey();
        if (googleKey.isBlank()) {
            logger.warn("Google Maps API key not configured for address search");
            return Collections.emptyList();
        }

        // 1. Try Google Places API (New) Autocomplete
        List<Map<String, Object>> placesResults = searchGooglePlacesNew(trimmedQuery, effectiveLimit, googleKey);
        if (!placesResults.isEmpty()) {
            return placesResults;
        }

        // 2. Try Google Geocoding API
        return searchGoogleGeocode(trimmedQuery, effectiveLimit, googleKey);
    }

    private List<Map<String, Object>> searchGooglePlacesNew(String query, int limit, String key) {
        try {
            String url = "https://places.googleapis.com/v1/places:autocomplete";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Goog-Api-Key", key);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("input", query);
            requestBody.put("includedRegionCodes", List.of("in"));
            requestBody.put("languageCode", "en");

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode suggestions = root.path("suggestions");
                if (suggestions.isArray() && suggestions.size() > 0) {
                    List<Map<String, Object>> list = new ArrayList<>();
                    int max = Math.min(limit, suggestions.size());
                    for (int i = 0; i < max; i++) {
                        JsonNode item = suggestions.get(i).path("placePrediction");
                        if (item.isMissingNode()) continue;

                        String placeId = item.path("placeId").asText("");
                        String text = item.path("text").path("text").asText("");
                        String mainText = item.path("structuredFormat").path("mainText").path("text").asText(text);
                        String secondaryText = item.path("structuredFormat").path("secondaryText").path("text").asText("");

                        Map<String, Object> map = new HashMap<>();
                        map.put("placeId", placeId);
                        map.put("displayName", text);
                        map.put("type", "establishment");

                        Map<String, Object> addrMap = new HashMap<>();
                        addrMap.put("road", mainText);
                        addrMap.put("city", secondaryText);
                        addrMap.put("country", "India");
                        map.put("address", addrMap);

                        if (!placeId.isBlank()) {
                            resolvePlaceCoordinates(placeId, key, map);
                        }
                        list.add(map);
                    }
                    if (!list.isEmpty()) {
                        return list;
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Google Places (New) autocomplete request failed: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    private void resolvePlaceCoordinates(String placeId, String key, Map<String, Object> targetMap) {
        try {
            String url = "https://places.googleapis.com/v1/places/" + placeId;
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Goog-Api-Key", key);
            headers.set("X-Goog-FieldMask", "id,formattedAddress,location,addressComponents");

            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode node = objectMapper.readTree(response.getBody());
                JsonNode loc = node.path("location");
                if (!loc.isMissingNode()) {
                    targetMap.put("lat", loc.path("latitude").asDouble());
                    targetMap.put("lon", loc.path("longitude").asDouble());
                }
                if (node.hasNonNull("formattedAddress")) {
                    targetMap.put("displayName", node.path("formattedAddress").asText());
                }
            }
        } catch (Exception ignored) {
        }
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
                } else {
                    logger.warn("Google Geocoding search returned status: {}", status);
                }
            }
        } catch (Exception e) {
            logger.warn("Google Geocode search failed: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * Reverse Geocoding using Google Maps
     * Validates latitude [-90, 90] and longitude [-180, 180].
     */
    public Map<String, Object> reverseGeocode(double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon) || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) {
            logger.warn("Invalid coordinate bounds provided to reverseGeocode: lat={}, lon={}", lat, lon);
            return Collections.emptyMap();
        }

        // Bounded spatial cache: quantized to 4 decimal places (~11m bucket) with 24-hr TTL
        String cacheKey = String.format(Locale.US, "%.4f,%.4f", lat, lon);
        GeoCacheEntry cached = reverseGeocodeCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.data;
        }

        String googleKey = getEffectiveGoogleMapsKey();
        if (googleKey.isBlank()) {
            logger.warn("Google Maps API key not configured for reverse geocoding");
            return Collections.emptyMap();
        }

        Map<String, Object> result = reverseGeocodeGoogle(lat, lon, googleKey);
        if (result != null && !result.isEmpty()) {
            reverseGeocodeCache.put(cacheKey, new GeoCacheEntry(result, 86_400_000L)); // 24 hours
        }
        return result;
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
                String status = root.path("status").asText("");
                if ("OK".equalsIgnoreCase(status)) {
                    JsonNode firstResult = root.path("results").get(0);
                    if (firstResult != null) {
                        Map<String, Object> map = new HashMap<>();
                        map.put("placeId", firstResult.path("place_id").asText(""));
                        map.put("displayName", firstResult.path("formatted_address").asText(""));
                        map.put("lat", firstResult.path("geometry").path("location").path("lat").asDouble());
                        map.put("lon", firstResult.path("geometry").path("location").path("lng").asDouble());
                        Map<String, Object> addrMap = parseGoogleAddressComponents(firstResult.path("address_components"));
                        map.put("address", addrMap);
                        map.put("pincode", addrMap.getOrDefault("pincode", addrMap.get("postcode")));
                        map.put("city", addrMap.get("city"));
                        map.put("state", addrMap.get("state"));
                        map.put("area", addrMap.getOrDefault("suburb", addrMap.get("city")));
                        map.put("houseNumber", addrMap.get("houseNumber"));
                        map.put("street", addrMap.get("road"));
                        return map;
                    }
                } else {
                    logger.warn("Google Reverse Geocoding returned status: {}", status);
                }
            }
        } catch (Exception e) {
            logger.warn("Google Reverse Geocoding failed: {}", e.getMessage());
        }
        return Collections.emptyMap();
    }

    /**
     * Road Routing & Driving Directions using Google Maps
     * Priority:
     * 1. Google Routes API v2 (modern gRPC/REST)
     * 2. Google Directions API (legacy)
     */
    public Map<String, Object> getDirections(double startLat, double startLng, double destLat, double destLng) {
        if (Double.isNaN(startLat) || Double.isNaN(startLng) || Double.isNaN(destLat) || Double.isNaN(destLng) ||
            startLat < -90.0 || startLat > 90.0 || destLat < -90.0 || destLat > 90.0 ||
            startLng < -180.0 || startLng > 180.0 || destLng < -180.0 || destLng > 180.0) {
            logger.warn("Invalid coordinate bounds provided for routing: [{}, {}] to [{}, {}]",
                    startLat, startLng, destLat, destLng);
            return Collections.emptyMap();
        }

        // Check LRU Route Cache (Quantized to ~100m grid bucket)
        String cacheKey = String.format(Locale.US, "%.3f,%.3f->%.3f,%.3f", startLat, startLng, destLat, destLng);
        RouteCacheEntry cached = routeCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.data;
        }

        String googleKey = getEffectiveGoogleMapsKey();
        if (googleKey.isBlank()) {
            logger.warn("Google Maps API key not configured for directions routing");
            return Collections.emptyMap();
        }

        // 1. Try modern Google Routes API v2
        Map<String, Object> routesV2 = getGoogleRoutesV2(startLat, startLng, destLat, destLng, googleKey);
        if (routesV2 != null && Boolean.TRUE.equals(routesV2.get("found"))) {
            routeCache.put(cacheKey, new RouteCacheEntry(routesV2, 300_000L)); // 5 min TTL
            return routesV2;
        }

        // 2. Try legacy Google Directions API
        Map<String, Object> directions = getGoogleDirections(startLat, startLng, destLat, destLng, googleKey);
        if (directions != null && Boolean.TRUE.equals(directions.get("found"))) {
            routeCache.put(cacheKey, new RouteCacheEntry(directions, 300_000L)); // 5 min TTL
        }
        return directions;
    }

    private Map<String, Object> getGoogleRoutesV2(double startLat, double startLng, double destLat, double destLng, String key) {
        try {
            String url = "https://routes.googleapis.com/directions/v2:computeRoutes";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Goog-Api-Key", key);
            headers.set("X-Goog-FieldMask", "routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline,routes.legs");

            Map<String, Object> originLatLng = Map.of("latitude", startLat, "longitude", startLng);
            Map<String, Object> originLocation = Map.of("latLng", originLatLng);
            Map<String, Object> origin = Map.of("location", originLocation);

            Map<String, Object> destLatLng = Map.of("latitude", destLat, "longitude", destLng);
            Map<String, Object> destLocation = Map.of("latLng", destLatLng);
            Map<String, Object> dest = Map.of("location", destLocation);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("origin", origin);
            requestBody.put("destination", dest);
            requestBody.put("travelMode", "DRIVE");
            requestBody.put("routingPreference", "TRAFFIC_AWARE");

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode routes = root.path("routes");
                if (routes.isArray() && routes.size() > 0) {
                    JsonNode firstRoute = routes.get(0);
                    double distanceMeters = firstRoute.path("distanceMeters").asDouble(0.0);
                    String durationStr = firstRoute.path("duration").asText(""); // e.g. "342s"
                    double durationSeconds = 0.0;
                    if (durationStr.endsWith("s")) {
                        try {
                            durationSeconds = Double.parseDouble(durationStr.substring(0, durationStr.length() - 1));
                        } catch (Exception ignored) {
                        }
                    }

                    String encodedPolyline = firstRoute.path("polyline").path("encodedPolyline").asText("");
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
                    result.put("distanceText", formatDistance(distanceMeters));
                    result.put("durationText", formatDuration(durationSeconds));
                    result.put("polyline", encodedPolyline);
                    result.put("directions", root);

                    logger.info("Google Routes API v2 route computed: {} m, {} s, {} points",
                            Math.round(distanceMeters), Math.round(durationSeconds), geoJsonCoordinates.size());
                    return result;
                }
            }
        } catch (Exception e) {
            logger.warn("Google Routes API v2 compute failed: {}", e.getMessage());
        }
        return Collections.emptyMap();
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
                } else {
                    logger.warn("Google Directions API returned status: {}", status);
                }
            }
        } catch (Exception e) {
            logger.warn("Google Directions API failed: {}", e.getMessage());
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
