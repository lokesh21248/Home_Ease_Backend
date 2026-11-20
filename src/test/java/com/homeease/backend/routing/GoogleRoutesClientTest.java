package com.homeease.backend.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.exception.RoutingException;
import com.homeease.backend.exception.RoutingException.Code;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the real HTTP client against an in-process fake of the Routes API, so the actual request shape
 * (path, headers, field mask, coordinate order) and every error translation are verified without the network.
 */
class GoogleRoutesClientTest {

    private static final String TEST_KEY = "unit-test-server-key-0123456789";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastApiKey = new AtomicReference<>();
    private final AtomicReference<String> lastFieldMask = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastQuery = new AtomicReference<>();

    private volatile int responseStatus = 200;
    private volatile String responseBody = "{}";
    private volatile String retryAfterHeader = null;
    private volatile long responseDelayMs = 0;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            lastPath.set(exchange.getRequestURI().getPath());
            lastQuery.set(exchange.getRequestURI().getRawQuery());
            lastApiKey.set(exchange.getRequestHeaders().getFirst("X-Goog-Api-Key"));
            lastFieldMask.set(exchange.getRequestHeaders().getFirst("X-Goog-FieldMask"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (responseDelayMs > 0) {
                try {
                    Thread.sleep(responseDelayMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (retryAfterHeader != null) {
                exchange.getResponseHeaders().add("Retry-After", retryAfterHeader);
            }
            exchange.sendResponseHeaders(responseStatus, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private GoogleRoutesClient client(String key, int readTimeoutMs) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new GoogleRoutesClient(key, base, "DRIVE", "TRAFFIC_AWARE", 1000, readTimeoutMs, MAPPER);
    }

    private static String goodResponse(String polyline) {
        return "{\"routes\":[{\"distanceMeters\":2412,\"duration\":\"754s\",\"staticDuration\":\"690s\","
                + "\"polyline\":{\"encodedPolyline\":\"" + polyline.replace("\\", "\\\\") + "\"}}]}";
    }

    private static String roadPolyline() {
        return PolylineCodec.encode(List.of(
                new double[]{17.44850, 78.37480},
                new double[]{17.45010, 78.37610},
                new double[]{17.45400, 78.38100}));
    }

    @Test
    void sendsCredentialOnlyInHeaderWithMinimalFieldMaskAndLatLngOrder() throws Exception {
        responseBody = goodResponse(roadPolyline());

        ComputedRoute route = client(TEST_KEY, 3000).computeRoute(17.4485, 78.3748, 17.4540, 78.3810);

        assertThat(lastPath.get()).isEqualTo("/directions/v2:computeRoutes");
        assertThat(lastApiKey.get()).isEqualTo(TEST_KEY);
        assertThat(lastQuery.get()).as("credential must never be placed in the URL").isNull();
        assertThat(lastFieldMask.get()).isEqualTo(GoogleRoutesClient.FIELD_MASK);

        JsonNode body = MAPPER.readTree(lastBody.get());
        assertThat(body.at("/origin/location/latLng/latitude").asDouble()).isEqualTo(17.4485);
        assertThat(body.at("/origin/location/latLng/longitude").asDouble()).isEqualTo(78.3748);
        assertThat(body.at("/destination/location/latLng/latitude").asDouble()).isEqualTo(17.4540);
        assertThat(body.at("/destination/location/latLng/longitude").asDouble()).isEqualTo(78.3810);
        assertThat(body.path("travelMode").asText()).isEqualTo("DRIVE");
        assertThat(body.path("routingPreference").asText()).isEqualTo("TRAFFIC_AWARE");

        assertThat(route.distanceMeters()).isEqualTo(2412);
        assertThat(route.durationSeconds()).isEqualTo(754);
        assertThat(route.staticDurationSeconds()).isEqualTo(690);
        assertThat(PolylineCodec.decode(route.encodedPolyline())).hasSize(3);
    }

    @Test
    void missingCredentialFailsWithoutAnyNetworkCall() {
        assertThatThrownBy(() -> client("  ", 3000).computeRoute(17.4, 78.3, 17.5, 78.4))
                .isInstanceOfSatisfying(RoutingException.class,
                        e -> assertThat(e.getCode()).isEqualTo(Code.ROUTING_NOT_CONFIGURED));
        assertThat(calls.get()).isZero();
    }

    @Test
    void emptyRoutesMeansNoRouteFound() {
        responseBody = "{}";
        assertThatThrownBy(() -> client(TEST_KEY, 3000).computeRoute(17.4, 78.3, 17.5, 78.4))
                .isInstanceOfSatisfying(RoutingException.class,
                        e -> assertThat(e.getCode()).isEqualTo(Code.NO_ROUTE_FOUND));
    }

    @Test
    void forbiddenFromProviderIsReportedAsNotConfiguredAndLeaksNothing() {
        responseStatus = 403;
        responseBody = "{\"error\":{\"code\":403,\"status\":\"PERMISSION_DENIED\","
                + "\"message\":\"Requests from this IP are blocked. key=" + TEST_KEY + "\"}}";

        assertThatThrownBy(() -> client(TEST_KEY, 3000).computeRoute(17.4, 78.3, 17.5, 78.4))
                .isInstanceOfSatisfying(RoutingException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(Code.ROUTING_NOT_CONFIGURED);
                    assertThat(e.isRetryable()).isFalse();
                    assertThat(e.getMessage()).doesNotContain(TEST_KEY).doesNotContain("PERMISSION_DENIED");
                });
    }

    @Test
    void quotaExhaustionMapsToRateLimitedAndHonoursRetryAfter() {
        responseStatus = 429;
        retryAfterHeader = "45";
        responseBody = "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}";

        assertThatThrownBy(() -> client(TEST_KEY, 3000).computeRoute(17.4, 78.3, 17.5, 78.4))
                .isInstanceOfSatisfying(RoutingException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(Code.ROUTING_RATE_LIMITED);
                    assertThat(e.isRetryable()).isTrue();
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(45);
                });
    }

    @Test
    void serverErrorMapsToRetryableProviderError() {
        responseStatus = 503;
        responseBody = "{\"error\":{\"status\":\"UNAVAILABLE\"}}";

        assertThatThrownBy(() -> client(TEST_KEY, 3000).computeRoute(17.4, 78.3, 17.5, 78.4))
                .isInstanceOfSatisfying(RoutingException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(Code.ROUTING_PROVIDER_ERROR);
                    assertThat(e.isRetryable()).isTrue();
                });
    }

    @Test
    void malformedGeometryIsRejectedBeforeReachingAnyClient() {
        responseBody = goodResponse("_p~iF~ps|U_ul"); // truncated polyline

        assertThatThrownBy(() -> client(TEST_KEY, 3000).computeRoute(17.4, 78.3, 17.5, 78.4))
                .isInstanceOfSatisfying(RoutingException.class,
                        e -> assertThat(e.getCode()).isEqualTo(Code.ROUTING_PROVIDER_ERROR));
    }

    @Test
    void routeWithoutDurationIsRejectedRatherThanInventingOne() {
        responseBody = "{\"routes\":[{\"distanceMeters\":100,\"polyline\":{\"encodedPolyline\":\""
                + roadPolyline().replace("\\", "\\\\") + "\"}}]}";

        assertThatThrownBy(() -> client(TEST_KEY, 3000).computeRoute(17.4, 78.3, 17.5, 78.4))
                .isInstanceOfSatisfying(RoutingException.class,
                        e -> assertThat(e.getCode()).isEqualTo(Code.ROUTING_PROVIDER_ERROR));
    }

    @Test
    void slowProviderTimesOutInsteadOfHangingTheRequestThread() {
        responseBody = goodResponse(roadPolyline());
        responseDelayMs = 1500;

        long start = System.nanoTime();
        assertThatThrownBy(() -> client(TEST_KEY, 300).computeRoute(17.4, 78.3, 17.5, 78.4))
                .isInstanceOfSatisfying(RoutingException.class,
                        e -> assertThat(e.getCode()).isEqualTo(Code.ROUTING_TIMEOUT));
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1400);
    }

    @Test
    void durationParsingHandlesFractionalAndInvalidValues() {
        assertThat(GoogleRoutesClient.parseDurationSeconds("754s")).isEqualTo(754);
        assertThat(GoogleRoutesClient.parseDurationSeconds("754.6s")).isEqualTo(755);
        assertThat(GoogleRoutesClient.parseDurationSeconds("")).isNull();
        assertThat(GoogleRoutesClient.parseDurationSeconds("abc")).isNull();
        assertThat(GoogleRoutesClient.parseDurationSeconds("-5s")).isNull();
        assertThat(GoogleRoutesClient.parseDurationSeconds("12")).isNull();
    }
}
