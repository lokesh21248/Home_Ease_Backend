package com.homeease.backend.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.exception.RoutingException;
import com.homeease.backend.exception.RoutingException.Code;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side client for the Google Routes API {@code computeRoutes} method.
 *
 * <p>The API key is read only from backend configuration ({@code GOOGLE_ROUTES_API_KEY}, falling back to
 * {@code GOOGLE_MAPS_API_KEY}) and is sent only to Google in the {@code X-Goog-Api-Key} header. It is never
 * logged, never placed in a URL, and never returned to callers. Provider response bodies are never surfaced
 * to callers either: failures are translated into {@link RoutingException} codes.</p>
 *
 * <p>Only the minimum field mask is requested, which keeps the call in the cheapest applicable SKU.</p>
 */
@Component
public class GoogleRoutesClient {

    private static final Logger log = LoggerFactory.getLogger(GoogleRoutesClient.class);

    static final String FIELD_MASK =
            "routes.duration,routes.staticDuration,routes.distanceMeters,routes.polyline.encodedPolyline";

    private final String apiKey;
    private final URI computeRoutesUri;
    private final String travelMode;
    private final String routingPreference;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Autowired
    public GoogleRoutesClient(
            @Value("${homeease.routes.google.api-key:}") String apiKey,
            @Value("${homeease.routes.google.base-url:https://routes.googleapis.com}") String baseUrl,
            @Value("${homeease.routes.google.travel-mode:DRIVE}") String travelMode,
            @Value("${homeease.routes.google.routing-preference:TRAFFIC_AWARE}") String routingPreference,
            @Value("${homeease.routes.google.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${homeease.routes.google.read-timeout-ms:5000}") int readTimeoutMs,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        String trimmedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.computeRoutesUri = URI.create(trimmedBase + "/directions/v2:computeRoutes");
        this.travelMode = travelMode;
        this.routingPreference = routingPreference == null ? "" : routingPreference.trim();
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.restTemplate = new RestTemplate(factory);

        if (this.apiKey.isEmpty()) {
            log.warn("Road routing is DISABLED: neither GOOGLE_ROUTES_API_KEY nor GOOGLE_MAPS_API_KEY is configured. "
                    + "GET /api/v1/tracking/{bookingId}/route will answer ROUTING_NOT_CONFIGURED.");
        }
    }

    public boolean isConfigured() {
        return !apiKey.isEmpty();
    }

    /**
     * Computes a road route. Coordinates must already be validated by the caller.
     *
     * @throws RoutingException with a stable {@link Code} on every failure path
     */
    public ComputedRoute computeRoute(double originLat, double originLng, double destLat, double destLng) {
        if (!isConfigured()) {
            throw new RoutingException(Code.ROUTING_NOT_CONFIGURED,
                    "Road routing is not available right now.");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Goog-Api-Key", apiKey);
        headers.set("X-Goog-FieldMask", FIELD_MASK);

        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange(computeRoutesUri, HttpMethod.POST,
                    new HttpEntity<>(buildRequestBody(originLat, originLng, destLat, destLng), headers),
                    String.class);
        } catch (HttpStatusCodeException e) {
            throw translateHttpError(e);
        } catch (ResourceAccessException e) {
            boolean timeout = e.getCause() instanceof SocketTimeoutException;
            log.warn("Routes API transport failure ({}): {}", timeout ? "timeout" : "io", e.getMessage());
            throw new RoutingException(timeout ? Code.ROUTING_TIMEOUT : Code.ROUTING_PROVIDER_ERROR,
                    "The routing service did not respond in time.", 15, e);
        } catch (RuntimeException e) {
            log.warn("Routes API unexpected failure: {}", e.getClass().getSimpleName());
            throw new RoutingException(Code.ROUTING_PROVIDER_ERROR,
                    "The routing service returned an unexpected error.", 15, e);
        }

        return parseResponse(response.getBody());
    }

    Map<String, Object> buildRequestBody(double originLat, double originLng, double destLat, double destLng) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("origin", waypoint(originLat, originLng));
        body.put("destination", waypoint(destLat, destLng));
        body.put("travelMode", travelMode);
        if (!routingPreference.isEmpty()) {
            body.put("routingPreference", routingPreference);
        }
        body.put("polylineQuality", "HIGH_QUALITY");
        body.put("polylineEncoding", "ENCODED_POLYLINE");
        return body;
    }

    private static Map<String, Object> waypoint(double lat, double lng) {
        Map<String, Object> latLng = new LinkedHashMap<>();
        latLng.put("latitude", lat);
        latLng.put("longitude", lng);
        return Map.of("location", Map.of("latLng", latLng));
    }

    ComputedRoute parseResponse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json == null ? "" : json);
            JsonNode routes = root.path("routes");
            if (!routes.isArray() || routes.isEmpty()) {
                throw new RoutingException(Code.NO_ROUTE_FOUND,
                        "No road route could be found between the partner and the service address.");
            }
            JsonNode route = routes.get(0);
            String encoded = route.path("polyline").path("encodedPolyline").asText("");
            if (encoded.isEmpty()) {
                throw new RoutingException(Code.ROUTING_PROVIDER_ERROR,
                        "The routing service returned a route without geometry.", 15);
            }
            List<double[]> points;
            try {
                points = PolylineCodec.decode(encoded);
            } catch (IllegalArgumentException e) {
                log.warn("Routes API returned a malformed polyline: {}", e.getMessage());
                throw new RoutingException(Code.ROUTING_PROVIDER_ERROR,
                        "The routing service returned invalid route geometry.", 15, e);
            }
            if (points.size() < 2) {
                throw new RoutingException(Code.NO_ROUTE_FOUND,
                        "No road route could be found between the partner and the service address.");
            }

            // Google omits distanceMeters when it is 0 (protobuf default); duration is a "123s" string.
            long distanceMeters = route.path("distanceMeters").asLong(0L);
            Long durationSeconds = parseDurationSeconds(route.path("duration").asText(""));
            if (durationSeconds == null) {
                throw new RoutingException(Code.ROUTING_PROVIDER_ERROR,
                        "The routing service returned a route without a duration.", 15);
            }
            Long staticSeconds = parseDurationSeconds(route.path("staticDuration").asText(""));
            return new ComputedRoute(encoded, distanceMeters, durationSeconds, staticSeconds);
        } catch (RoutingException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Routes API response could not be parsed: {}", e.getClass().getSimpleName());
            throw new RoutingException(Code.ROUTING_PROVIDER_ERROR,
                    "The routing service returned an unreadable response.", 15, e);
        }
    }

    /** Parses a protobuf JSON duration such as {@code "754s"} or {@code "754.5s"}. */
    static Long parseDurationSeconds(String raw) {
        if (raw == null || raw.length() < 2 || !raw.endsWith("s")) {
            return null;
        }
        try {
            double seconds = Double.parseDouble(raw.substring(0, raw.length() - 1));
            if (!Double.isFinite(seconds) || seconds < 0) {
                return null;
            }
            return Math.round(seconds);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private RoutingException translateHttpError(HttpStatusCodeException e) {
        int status = e.getStatusCode().value();
        String providerStatus = extractProviderStatus(e.getResponseBodyAsString());
        if (status == 401 || status == 403) {
            // Operationally actionable (key restriction / API not enabled) but meaningless to the customer.
            log.error("Routes API rejected the server credential: http={} providerStatus={}. "
                    + "Check that the Routes API is enabled for the key and that its restrictions allow this server.",
                    status, providerStatus);
            return new RoutingException(Code.ROUTING_NOT_CONFIGURED, "Road routing is not available right now.");
        }
        if (status == 429) {
            int retryAfter = parseRetryAfter(e.getResponseHeaders());
            log.warn("Routes API quota/rate limit reached: providerStatus={} retryAfter={}s", providerStatus, retryAfter);
            return new RoutingException(Code.ROUTING_RATE_LIMITED,
                    "Road routing is busy. Please try again shortly.", retryAfter);
        }
        log.warn("Routes API error: http={} providerStatus={}", status, providerStatus);
        return new RoutingException(Code.ROUTING_PROVIDER_ERROR,
                "The routing service is temporarily unavailable.", 15);
    }

    /** Extracts only Google's short status token (e.g. PERMISSION_DENIED) - never the full body. */
    private String extractProviderStatus(String body) {
        try {
            if (body == null || body.isBlank()) {
                return "UNKNOWN";
            }
            String s = objectMapper.readTree(body).path("error").path("status").asText("UNKNOWN");
            return s.length() > 40 ? s.substring(0, 40) : s;
        } catch (Exception ignored) {
            return "UNPARSEABLE";
        }
    }

    private static int parseRetryAfter(HttpHeaders headers) {
        try {
            if (headers != null && headers.getFirst("Retry-After") != null) {
                int v = Integer.parseInt(headers.getFirst("Retry-After").trim());
                return Math.max(1, Math.min(v, 300));
            }
        } catch (NumberFormatException ignored) {
            // fall through to default
        }
        return 60;
    }
}
