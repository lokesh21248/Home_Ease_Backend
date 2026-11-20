package com.homeease.backend.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.dto.BookingRouteResponse;
import com.homeease.backend.exception.RoutingException;
import com.homeease.backend.exception.RoutingException.Code;
import com.homeease.backend.model.enums.BookingStage;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Whole-journey scenario: real HTTP client -> fake Routes API, real route service, only the database loader
 * and Redis are test doubles. Mirrors the booking lifecycle the customer app lives through.
 */
@SuppressWarnings("unchecked")
class BookingRouteLifecycleScenarioTest {

    private static final double DEST_LAT = 17.5014;
    private static final double DEST_LNG = 78.5390;
    private static final UUID BOOKING = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID WORKER = UUID.randomUUID();
    private static final UUID WORKER_ENTITY = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer fakeRoutesApi;
    private final AtomicInteger providerCalls = new AtomicInteger();
    private final AtomicReference<JsonNode> lastRequest = new AtomicReference<>();
    private volatile int providerStatus = 200;

    private BookingRouteContextLoader loader;
    private ValueOperations<String, String> redisOps;
    private Clock clock;
    private long nowMs = 1_800_000_000_000L;
    private BookingRouteService service;

    @BeforeEach
    void setUp() throws IOException {
        fakeRoutesApi = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeRoutesApi.createContext("/", exchange -> {
            providerCalls.incrementAndGet();
            JsonNode req = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
            lastRequest.set(req);
            byte[] out;
            int status = providerStatus;
            if (status != 200) {
                out = "{\"error\":{\"status\":\"UNAVAILABLE\"}}".getBytes(StandardCharsets.UTF_8);
            } else {
                double oLat = req.at("/origin/location/latLng/latitude").asDouble();
                double oLng = req.at("/origin/location/latLng/longitude").asDouble();
                double dLat = req.at("/destination/location/latLng/latitude").asDouble();
                double dLng = req.at("/destination/location/latLng/longitude").asDouble();
                // A road that bends: origin -> corner -> destination (not a straight line).
                String poly = PolylineCodec.encode(List.of(
                        new double[]{oLat, oLng}, new double[]{oLat, dLng}, new double[]{dLat, dLng}));
                double meters = GeoMath.haversineMeters(oLat, oLng, oLat, dLng)
                        + GeoMath.haversineMeters(oLat, dLng, dLat, dLng);
                long seconds = Math.round(meters / 6.0); // fake provider's own speed model
                out = ("{\"routes\":[{\"distanceMeters\":" + Math.round(meters) + ",\"duration\":\"" + seconds
                        + "s\",\"polyline\":{\"encodedPolyline\":\"" + poly.replace("\\", "\\\\") + "\"}}]}")
                        .getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        fakeRoutesApi.start();

        GoogleRoutesClient client = new GoogleRoutesClient("scenario-key",
                "http://127.0.0.1:" + fakeRoutesApi.getAddress().getPort(), "DRIVE", "TRAFFIC_AWARE", 1000, 3000, MAPPER);

        loader = mock(BookingRouteContextLoader.class);
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        redisOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(redisOps);
        clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId z) { return this; }
            @Override public long millis() { return nowMs; }
            @Override public Instant instant() { return Instant.ofEpochMilli(nowMs); }
        };
        service = new BookingRouteService(loader, redis, MAPPER, client, clock, 20, 8, 60, 60.0, 120, 20);
    }

    @AfterEach
    void tearDown() {
        fakeRoutesApi.stop(0);
    }

    private void bookingIs(BookingStage stage) {
        when(loader.load(BOOKING)).thenReturn(Optional.of(new RouteContext(BOOKING, stage, CUSTOMER, WORKER,
                WORKER_ENTITY, DEST_LAT, DEST_LNG)));
    }

    private void workerPublishes(double lat, double lng) {
        when(redisOps.get("tracking:booking:" + BOOKING)).thenReturn("{\"bookingId\":\"" + BOOKING + "\",\"lat\":" + lat
                + ",\"lng\":" + lng + ",\"timestamp\":" + nowMs + ",\"serverTimestamp\":" + nowMs
                + ",\"trackingActive\":true}");
    }

    private BookingRouteResponse customerAsks(RouteRefreshReason reason) {
        return service.getRoute(BOOKING, CUSTOMER, false, reason);
    }

    private static void assertCode(Runnable call, Code code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(RoutingException.class, e -> assertThat(e.getCode()).isEqualTo(code));
    }

    @Test
    void scheduledBookingJourneyFromAssignmentToCompletion() {
        // Scheduled booking, partner assigned days ahead, nobody travelling yet.
        bookingIs(BookingStage.ACCEPTED);
        when(redisOps.get("tracking:booking:" + BOOKING)).thenReturn(null);
        assertCode(() -> customerAsks(RouteRefreshReason.INITIAL), Code.WORKER_LOCATION_UNAVAILABLE);
        assertThat(providerCalls.get()).as("no paid call without a worker fix").isZero();

        // Worker starts travelling and publishes the first GPS fix.
        bookingIs(BookingStage.ON_THE_WAY);
        workerPublishes(17.4600, 78.5100);
        BookingRouteResponse first = customerAsks(RouteRefreshReason.INITIAL);

        assertThat(providerCalls.get()).isEqualTo(1);
        JsonNode sent = lastRequest.get();
        assertThat(sent.at("/origin/location/latLng/latitude").asDouble()).isEqualTo(17.4600);
        assertThat(sent.at("/destination/location/latLng/latitude").asDouble()).as("persisted address").isEqualTo(DEST_LAT);
        assertThat(sent.at("/destination/location/latLng/longitude").asDouble()).isEqualTo(DEST_LNG);

        List<double[]> line = PolylineCodec.decode(first.polyline());
        assertThat(line).as("road with a bend, not a 2-point straight line").hasSize(3);
        assertThat(line.get(0)[0]).isEqualTo(17.4600, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(line.get(2)[0]).isEqualTo(DEST_LAT, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(line.get(2)[1]).isEqualTo(DEST_LNG, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(first.distanceMeters()).isPositive();
        assertThat(first.durationSeconds()).isPositive();

        // GPS ticks every 5 s (3 ticks = 15 s, inside the 20 s window): the app may call often, the paid API is
        // not called again.
        for (int i = 0; i < 3; i++) {
            nowMs += 5_000;
            workerPublishes(17.4600 + i * 0.0002, 78.5100);
            assertThat(customerAsks(RouteRefreshReason.PERIODIC).cached()).isTrue();
        }
        assertThat(providerCalls.get()).isEqualTo(1);

        // ~60 s later the worker has moved well along the road: ETA refresh, new origin.
        nowMs += 40_000;
        workerPublishes(17.4700, 78.5200);
        BookingRouteResponse refreshed = customerAsks(RouteRefreshReason.PERIODIC);
        assertThat(refreshed.cached()).isFalse();
        assertThat(refreshed.origin().lat()).isEqualTo(17.4700);
        assertThat(refreshed.distanceMeters()).as("closer than at the start").isLessThan(first.distanceMeters());
        assertThat(providerCalls.get()).isEqualTo(2);

        // Worker takes a different road; the app reports OFF_ROUTE and gets a fresh route soon after.
        nowMs += 9_000;
        workerPublishes(17.4750, 78.5600);
        BookingRouteResponse rerouted = customerAsks(RouteRefreshReason.OFF_ROUTE);
        assertThat(rerouted.cached()).isFalse();
        assertThat(rerouted.origin().lng()).isEqualTo(78.5600);
        assertThat(providerCalls.get()).isEqualTo(3);

        // A stranger can never read this booking's route, at any point.
        assertCode(() -> service.getRoute(BOOKING, STRANGER, false, RouteRefreshReason.PERIODIC), Code.NOT_BOOKING_PARTICIPANT);

        // Worker arrives: the route is no longer served and the paid API is not touched.
        bookingIs(BookingStage.ARRIVED);
        assertCode(() -> customerAsks(RouteRefreshReason.PERIODIC), Code.TRACKING_NOT_ACTIVE);
        bookingIs(BookingStage.IN_PROGRESS);
        assertCode(() -> customerAsks(RouteRefreshReason.PERIODIC), Code.TRACKING_NOT_ACTIVE);
        bookingIs(BookingStage.COMPLETED);
        assertCode(() -> customerAsks(RouteRefreshReason.PERIODIC), Code.TRACKING_NOT_ACTIVE);
        assertThat(providerCalls.get()).isEqualTo(3);
    }

    @Test
    void providerOutageIsContainedBackedOffAndRecovers() {
        bookingIs(BookingStage.ON_THE_WAY);
        workerPublishes(17.4600, 78.5100);
        providerStatus = 503;

        assertCode(() -> customerAsks(RouteRefreshReason.INITIAL), Code.ROUTING_PROVIDER_ERROR);
        for (int i = 0; i < 3; i++) { // clients keep asking while the outage lasts
            nowMs += 5_000;
            workerPublishes(17.4600, 78.5100);
            assertCode(() -> customerAsks(RouteRefreshReason.PERIODIC), Code.ROUTING_PROVIDER_ERROR);
        }
        assertThat(providerCalls.get()).as("failing provider is not hammered").isEqualTo(1);

        providerStatus = 200;
        nowMs += 10_000; // backoff elapsed
        workerPublishes(17.4600, 78.5100);
        assertThat(customerAsks(RouteRefreshReason.PERIODIC).found()).isTrue();
        assertThat(providerCalls.get()).isEqualTo(2);
    }

    @Test
    void missingServerCredentialIsReportedHonestlyAndNeverCallsOut() {
        GoogleRoutesClient unconfigured = new GoogleRoutesClient("",
                "http://127.0.0.1:" + fakeRoutesApi.getAddress().getPort(), "DRIVE", "TRAFFIC_AWARE", 1000, 3000, MAPPER);
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        when(redis.opsForValue()).thenReturn(redisOps);
        BookingRouteService noKey = new BookingRouteService(loader, redis, MAPPER, unconfigured, clock, 20, 8, 60, 60.0, 120, 20);
        bookingIs(BookingStage.ON_THE_WAY);
        workerPublishes(17.4600, 78.5100);

        assertThatThrownBy(() -> noKey.getRoute(BOOKING, CUSTOMER, false, RouteRefreshReason.INITIAL))
                .isInstanceOfSatisfying(RoutingException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(Code.ROUTING_NOT_CONFIGURED);
                    assertThat(e.getMessage()).doesNotContain("key").doesNotContain("GOOGLE");
                });
        assertThat(providerCalls.get()).isZero();
    }
}
