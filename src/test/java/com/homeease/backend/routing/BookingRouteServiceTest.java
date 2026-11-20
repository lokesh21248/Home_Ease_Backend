package com.homeease.backend.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.dto.BookingRouteResponse;
import com.homeease.backend.exception.RoutingException;
import com.homeease.backend.exception.RoutingException.Code;
import com.homeease.backend.model.enums.BookingStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class BookingRouteServiceTest {

    // Persisted booking address (Alwal, Hyderabad). Deliberately different from every other coordinate below
    // so any substitution (customer GPS, pincode centroid, hard-coded default) is caught by the assertions.
    private static final double DEST_LAT = 17.5014;
    private static final double DEST_LNG = 78.5390;

    private static final UUID BOOKING = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID WORKER_USER = UUID.randomUUID();
    private static final UUID WORKER_ENTITY = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();

    private static final String POLYLINE = PolylineCodec.encode(List.of(
            new double[]{17.4600, 78.5100},
            new double[]{17.4800, 78.5250},
            new double[]{DEST_LAT, DEST_LNG}));
    private static final ComputedRoute ROUTE = new ComputedRoute(POLYLINE, 2412, 754, 690L);

    private BookingRouteContextLoader loader;
    private RedisTemplate<String, String> redis;
    private ValueOperations<String, String> valueOps;
    private GoogleRoutesClient provider;
    private MutableClock clock;
    private BookingRouteService service;

    @BeforeEach
    void setUp() {
        loader = mock(BookingRouteContextLoader.class);
        redis = mock(RedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        provider = mock(GoogleRoutesClient.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        clock = new MutableClock(1_800_000_000_000L);
        // min-interval 20s, off-route floor 8s, max-age 60s, reuse radius 60m, stale 120s, error backoff 20s
        service = new BookingRouteService(loader, redis, new ObjectMapper(), provider, clock,
                20, 8, 60, 60.0, 120, 20);

        givenBooking(BookingStage.ON_THE_WAY, DEST_LAT, DEST_LNG, true);
        givenWorkerAt(17.4600, 78.5100, clock.millis());
        when(provider.computeRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(ROUTE);
    }

    // ───────────────────────── helpers ─────────────────────────

    private void givenBooking(BookingStage status, Double lat, Double lng, boolean withWorker) {
        RouteContext ctx = new RouteContext(BOOKING, status, CUSTOMER,
                withWorker ? WORKER_USER : null, withWorker ? WORKER_ENTITY : null, lat, lng);
        when(loader.load(BOOKING)).thenReturn(Optional.of(ctx));
    }

    private void givenWorkerAt(double lat, double lng, long recordedAtMs) {
        when(valueOps.get("tracking:booking:" + BOOKING)).thenReturn(locationJson(lat, lng, recordedAtMs, true));
    }

    private static String locationJson(double lat, double lng, long ts, boolean active) {
        return "{\"bookingId\":\"" + BOOKING + "\",\"lat\":" + lat + ",\"lng\":" + lng
                + ",\"timestamp\":" + ts + ",\"serverTimestamp\":" + ts + ",\"trackingActive\":" + active + "}";
    }

    private BookingRouteResponse asCustomer(RouteRefreshReason reason) {
        return service.getRoute(BOOKING, CUSTOMER, false, reason);
    }

    private static void expectCode(Runnable call, Code expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(RoutingException.class,
                e -> assertThat(e.getCode()).isEqualTo(expected));
    }

    // ───────────────────────── origin / destination integrity ─────────────────────────

    @Test
    void routesFromWorkerGpsToThePersistedBookingAddressAndNothingElse() {
        BookingRouteResponse r = asCustomer(RouteRefreshReason.INITIAL);

        verify(provider).computeRoute(eq(17.4600), eq(78.5100), eq(DEST_LAT), eq(DEST_LNG));
        assertThat(r.found()).isTrue();
        assertThat(r.status()).isEqualTo("ON_THE_WAY");
        assertThat(r.polyline()).isEqualTo(POLYLINE);
        assertThat(r.distanceMeters()).isEqualTo(2412);
        assertThat(r.durationSeconds()).isEqualTo(754);
        assertThat(r.staticDurationSeconds()).isEqualTo(690);
        assertThat(r.destination().lat()).isEqualTo(DEST_LAT);
        assertThat(r.destination().lng()).isEqualTo(DEST_LNG);
        assertThat(r.origin().lat()).isEqualTo(17.4600);
        assertThat(r.cached()).isFalse();
        assertThat(r.provider()).isEqualTo("google-routes-v2");
        assertThat(r.polylineEncoding()).isEqualTo("ENCODED_POLYLINE_1E5");
    }

    @ParameterizedTest
    @EnumSource(value = BookingStage.class, names = {"ACCEPTED", "CONFIRMED", "ON_THE_WAY"})
    void routeIsAvailableThroughoutTheTravelStageIncludingScheduledBookingsAfterAssignment(BookingStage stage) {
        givenBooking(stage, DEST_LAT, DEST_LNG, true);

        assertThat(asCustomer(RouteRefreshReason.INITIAL).found()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = BookingStage.class,
            names = {"PENDING", "REQUESTED", "SEARCHING", "WORKER_NOT_FOUND", "ARRIVED", "IN_PROGRESS",
                    "COMPLETED", "CANCELLED", "REJECTED", "EXPIRED"})
    void noRouteIsServedOutsideTheTravelStage(BookingStage stage) {
        givenBooking(stage, DEST_LAT, DEST_LNG, true);

        expectCode(() -> asCustomer(RouteRefreshReason.INITIAL), Code.TRACKING_NOT_ACTIVE);
        verify(provider, never()).computeRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void bookingWithoutAnAssignedWorkerHasNoRoute() {
        givenBooking(BookingStage.ACCEPTED, DEST_LAT, DEST_LNG, false);
        expectCode(() -> asCustomer(RouteRefreshReason.INITIAL), Code.WORKER_NOT_ASSIGNED);
    }

    @Test
    void missingOrPlaceholderDestinationIsRefusedNotReplacedWithADefault() {
        for (double[] bad : new double[][]{{0.0, 0.0}, {91.0, 78.0}, {17.0, 181.0}, {Double.NaN, 78.0}}) {
            givenBooking(BookingStage.ON_THE_WAY, bad[0], bad[1], true);
            expectCode(() -> asCustomer(RouteRefreshReason.INITIAL), Code.DESTINATION_UNAVAILABLE);
        }
        givenBooking(BookingStage.ON_THE_WAY, null, null, true);
        expectCode(() -> asCustomer(RouteRefreshReason.INITIAL), Code.DESTINATION_UNAVAILABLE);
        verify(provider, never()).computeRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    // ───────────────────────── authorization ─────────────────────────

    @Test
    void assignedWorkerByUserIdOrWorkerEntityIdAndAdminMayReadTheRoute() {
        assertThat(service.getRoute(BOOKING, WORKER_USER, false, RouteRefreshReason.INITIAL).found()).isTrue();
        assertThat(service.getRoute(BOOKING, WORKER_ENTITY, false, RouteRefreshReason.INITIAL).found()).isTrue();
        assertThat(service.getRoute(BOOKING, STRANGER, true, RouteRefreshReason.INITIAL).found()).isTrue();
    }

    @Test
    void strangersAreRefusedBeforeAnyLocationOrProviderAccess() {
        expectCode(() -> service.getRoute(BOOKING, STRANGER, false, RouteRefreshReason.INITIAL),
                Code.NOT_BOOKING_PARTICIPANT);

        verify(valueOps, never()).get("tracking:booking:" + BOOKING);
        verify(provider, never()).computeRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void anotherBookingsWorkerCannotReadThisRoute() {
        // A real worker (assigned elsewhere) is just a stranger to this booking.
        expectCode(() -> service.getRoute(BOOKING, UUID.randomUUID(), false, RouteRefreshReason.PERIODIC),
                Code.NOT_BOOKING_PARTICIPANT);
    }

    @Test
    void unknownBookingAndMissingIdentityAreRejected() {
        when(loader.load(BOOKING)).thenReturn(Optional.empty());
        expectCode(() -> asCustomer(RouteRefreshReason.INITIAL), Code.BOOKING_NOT_FOUND);
        expectCode(() -> service.getRoute(BOOKING, null, false, RouteRefreshReason.INITIAL), Code.JWT_REQUIRED);
    }

    // ───────────────────────── worker GPS validity ─────────────────────────

    @Test
    void noWorkerFixYetIsReportedAsUnavailable() {
        when(valueOps.get("tracking:booking:" + BOOKING)).thenReturn(null);
        expectCode(() -> asCustomer(RouteRefreshReason.INITIAL), Code.WORKER_LOCATION_UNAVAILABLE);
    }

    @Test
    void closedTrackingSessionOrGarbageFixIsUnavailable() {
        when(valueOps.get("tracking:booking:" + BOOKING)).thenReturn(locationJson(17.46, 78.51, clock.millis(), false));
        expectCode(() -> asCustomer(RouteRefreshReason.INITIAL), Code.WORKER_LOCATION_UNAVAILABLE);

        when(valueOps.get("tracking:booking:" + BOOKING)).thenReturn(locationJson(0.0, 0.0, clock.millis(), true));
        expectCode(() -> asCustomer(RouteRefreshReason.INITIAL), Code.WORKER_LOCATION_UNAVAILABLE);

        when(valueOps.get("tracking:booking:" + BOOKING)).thenReturn("not json");
        expectCode(() -> asCustomer(RouteRefreshReason.INITIAL), Code.WORKER_LOCATION_UNAVAILABLE);
    }

    @Test
    void staleWorkerFixIsRefusedInsteadOfRoutingFromAnOldPosition() {
        givenWorkerAt(17.46, 78.51, clock.millis() - 121_000);
        expectCode(() -> asCustomer(RouteRefreshReason.INITIAL), Code.WORKER_LOCATION_STALE);

        givenWorkerAt(17.46, 78.51, clock.millis() - 119_000);
        assertThat(asCustomer(RouteRefreshReason.INITIAL).found()).isTrue();
    }

    @Test
    void redisOutageDegradesToUnavailableNotA500() {
        when(valueOps.get("tracking:booking:" + BOOKING)).thenThrow(new IllegalStateException("redis down"));
        expectCode(() -> asCustomer(RouteRefreshReason.INITIAL), Code.WORKER_LOCATION_UNAVAILABLE);
    }

    // ───────────────────────── paid-API cost control ─────────────────────────

    @Test
    void repeatedCallsWithinTheMinimumIntervalMakeOneProviderCall() {
        asCustomer(RouteRefreshReason.INITIAL);
        for (int tick = 1; tick <= 3; tick++) { // GPS ticks every ~5s
            clock.advanceMs(5_000);
            givenWorkerAt(17.4600 + tick * 0.001, 78.5100, clock.millis());
            BookingRouteResponse r = asCustomer(RouteRefreshReason.PERIODIC);
            assertThat(r.cached()).isTrue();
        }
        verify(provider, times(1)).computeRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void routeIsReusedUpToMaxAgeWhileTheWorkerBarelyMovesThenRefreshed() {
        asCustomer(RouteRefreshReason.INITIAL);

        clock.advanceMs(40_000); // past min-interval, inside max-age, worker still ~at origin
        givenWorkerAt(17.46010, 78.5100, clock.millis());
        assertThat(asCustomer(RouteRefreshReason.PERIODIC).cached()).isTrue();
        verify(provider, times(1)).computeRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());

        clock.advanceMs(25_000); // 65s old -> exceeds max-age
        givenWorkerAt(17.46010, 78.5100, clock.millis());
        assertThat(asCustomer(RouteRefreshReason.PERIODIC).cached()).isFalse();
        verify(provider, times(2)).computeRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void routeIsRefreshedAfterMinIntervalOnceTheWorkerHasMovedFarEnough() {
        asCustomer(RouteRefreshReason.INITIAL);

        clock.advanceMs(25_000);
        givenWorkerAt(17.4700, 78.5100, clock.millis()); // ~1.1 km away
        BookingRouteResponse r = asCustomer(RouteRefreshReason.PERIODIC);

        assertThat(r.cached()).isFalse();
        assertThat(r.origin().lat()).isEqualTo(17.4700);
        verify(provider, times(2)).computeRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void offRouteRerouteIsAllowedSoonerThanAPeriodicRefreshButNotBelowTheHardFloor() {
        asCustomer(RouteRefreshReason.INITIAL);

        clock.advanceMs(5_000); // below the 8s floor: still served from cache even though off-route
        givenWorkerAt(17.4650, 78.5200, clock.millis());
        assertThat(asCustomer(RouteRefreshReason.OFF_ROUTE).cached()).isTrue();

        clock.advanceMs(4_000); // 9s old: allowed to recompute although < min-interval (20s)
        givenWorkerAt(17.4650, 78.5200, clock.millis());
        BookingRouteResponse r = asCustomer(RouteRefreshReason.OFF_ROUTE);
        assertThat(r.cached()).isFalse();
        assertThat(r.origin().lng()).isEqualTo(78.5200);
        verify(provider, times(2)).computeRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void providerFailureIsNegativeCachedSoClientsCannotHammerAFailingPaidApi() {
        when(provider.computeRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenThrow(new RoutingException(Code.ROUTING_PROVIDER_ERROR, "down", 15));

        expectCode(() -> asCustomer(RouteRefreshReason.INITIAL), Code.ROUTING_PROVIDER_ERROR);
        clock.advanceMs(5_000);
        givenWorkerAt(17.4600, 78.5100, clock.millis());
        assertThatThrownBy(() -> asCustomer(RouteRefreshReason.INITIAL))
                .isInstanceOfSatisfying(RoutingException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(Code.ROUTING_PROVIDER_ERROR);
                    assertThat(e.getRetryAfterSeconds()).isBetween(1, 20);
                });
        verify(provider, times(1)).computeRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());

        clock.advanceMs(20_000); // backoff over: one more attempt is allowed
        givenWorkerAt(17.4600, 78.5100, clock.millis());
        // doReturn(): re-stubbing with when(mock.call()) would invoke the previous (throwing) stub.
        org.mockito.Mockito.doReturn(ROUTE).when(provider)
                .computeRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
        assertThat(asCustomer(RouteRefreshReason.INITIAL).found()).isTrue();
        verify(provider, times(2)).computeRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void leavingTheTravelStageDropsTheCachedRoute() {
        asCustomer(RouteRefreshReason.INITIAL);

        givenBooking(BookingStage.ARRIVED, DEST_LAT, DEST_LNG, true);
        expectCode(() -> asCustomer(RouteRefreshReason.PERIODIC), Code.TRACKING_NOT_ACTIVE);

        // Back in the travel stage (e.g. reassignment) must not resurrect the old route.
        givenBooking(BookingStage.ON_THE_WAY, DEST_LAT, DEST_LNG, true);
        clock.advanceMs(1_000);
        givenWorkerAt(17.4600, 78.5100, clock.millis());
        assertThat(asCustomer(RouteRefreshReason.PERIODIC).cached()).isFalse();
    }

    @Test
    void aChangedDestinationBypassesTheCache() {
        asCustomer(RouteRefreshReason.INITIAL);

        givenBooking(BookingStage.ON_THE_WAY, 17.6000, 78.6000, true);
        clock.advanceMs(1_000);
        givenWorkerAt(17.4600, 78.5100, clock.millis());

        BookingRouteResponse r = asCustomer(RouteRefreshReason.PERIODIC);
        assertThat(r.cached()).isFalse();
        assertThat(r.destination().lat()).isEqualTo(17.6000);
        verify(provider).computeRoute(anyDouble(), anyDouble(), eq(17.6000), eq(78.6000));
    }

    @Test
    void concurrentCallersForOneBookingShareASingleProviderCall() throws Exception {
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        when(provider.computeRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenAnswer(inv -> {
            providerEntered.countDown();
            assertThat(releaseProvider.await(5, TimeUnit.SECONDS)).isTrue();
            return ROUTE;
        });

        int callers = 12;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<BookingRouteResponse>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> asCustomer(RouteRefreshReason.INITIAL)));
            assertThat(providerEntered.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 1; i < callers; i++) {
                futures.add(pool.submit(() -> service.getRoute(BOOKING, WORKER_USER, false, RouteRefreshReason.INITIAL)));
            }
            Thread.sleep(250); // let followers park on the in-flight call
            releaseProvider.countDown();

            int fresh = 0;
            for (Future<BookingRouteResponse> f : futures) {
                BookingRouteResponse r = f.get(10, TimeUnit.SECONDS);
                assertThat(r.polyline()).isEqualTo(POLYLINE);
                if (!r.cached()) {
                    fresh++;
                }
            }
            assertThat(fresh).as("exactly one caller triggers the paid call").isEqualTo(1);
            verify(provider, times(1)).computeRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
        } finally {
            pool.shutdownNow();
        }
    }

    // ───────────────────────── clock ─────────────────────────

    private static final class MutableClock extends Clock {
        private volatile long nowMs;

        MutableClock(long startMs) {
            this.nowMs = startMs;
        }

        void advanceMs(long delta) {
            nowMs += delta;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public long millis() {
            return nowMs;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(nowMs);
        }
    }
}
