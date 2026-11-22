package com.homeease.backend.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.homeease.backend.tracking.ArrivalGeofence.Verdict;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * In-process concurrency check of the per-fix server work for 1,000 simultaneous tracking sessions:
 * GpsFixValidator -> ArrivalGeofence -> LocationHistoryRecorder.offer, on 32 threads.
 *
 * <p>What it proves: thread-safety, strict per-booking isolation (every booking arrives exactly once, at its own
 * address, and no booking's fixes affect another's), bounded memory, and the CPU cost per fix. What it does NOT
 * prove: network, Redis (Upstash) latency, WebSocket fan-out or database throughput - those need the HTTP/STOMP
 * benchmark (load-test/tracking_benchmark.js) against a staging stack.</p>
 */
class TrackingConcurrencyLoadTest {

    static final int SESSIONS = 1_000;
    static final int THREADS = 32;

    @Test
    @DisplayName("1,000 concurrent sessions x 40 fixes: isolated, exactly one arrival each, bounded state")
    void thousandConcurrentSessions() throws Exception {
        GpsFixValidator validator = new GpsFixValidator();
        ArrivalGeofence geofence = new ArrivalGeofence();
        LocationHistoryRecorder recorder = new LocationHistoryRecorder(mock(JdbcTemplate.class), true, 15, 30.0,
                200_000, 500, 30);

        // each booking gets its own destination spread over ~20 km so no two share an address
        Map<UUID, double[]> destinations = new ConcurrentHashMap<>();
        List<UUID> bookings = new ArrayList<>();
        for (int i = 0; i < SESSIONS; i++) {
            UUID id = UUID.randomUUID();
            bookings.add(id);
            destinations.put(id, new double[]{17.30 + (i % 40) * 0.005, 78.30 + (i / 40) * 0.005});
        }

        Map<UUID, AtomicInteger> arrivals = new ConcurrentHashMap<>();
        AtomicLong fixes = new AtomicLong();
        AtomicLong rejected = new AtomicLong();
        long now = System.currentTimeMillis();
        long t0 = now - 100_000;

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        // one task per booking: the partner drives 900 m -> 100 m (33 fixes), then stops at the door (7 slow fixes)
        for (UUID id : bookings) {
            futures.add(pool.submit(() -> {
                start.await();
                double[] dest = destinations.get(id);
                GpsFix previous = null;
                long ts = t0;
                for (int k = 0; k < 40; k++) {
                    double meters = k < 33 ? 900 - k * 25 : 50 - (k - 33) * 3;
                    double speed = k < 33 ? 12.5 : 0.4;
                    GpsFixValidator.Result r = validator.validate(new GpsFixValidator.Raw(
                            dest[0] + meters / 111_195.0, dest[1], ts, 6.0, speed, 180.0), previous, now);
                    if (!r.accepted()) {
                        rejected.incrementAndGet();
                    } else {
                        previous = r.fix();
                        fixes.incrementAndGet();
                        recorder.offer(id, null, r.fix(), now);
                        if (geofence.evaluate(id, r.fix(), dest[0], dest[1]).verdict() == Verdict.ARRIVED) {
                            arrivals.computeIfAbsent(id, x -> new AtomicInteger()).incrementAndGet();
                            geofence.reset(id);
                            // as in production: the booking is now ARRIVED and the server refuses further GPS
                            return null;
                        }
                    }
                    ts += k < 33 ? 2_000 : 3_000;
                }
                return null;
            }));
        }
        long startNs = System.nanoTime();
        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
        pool.shutdown();

        long total = fixes.get();
        System.out.printf("[load] %d sessions, %d fixes accepted, %d rejected, %d ms, %.0f fixes/s, %d history rows queued%n",
                SESSIONS, total, rejected.get(), elapsedMs, total * 1000.0 / Math.max(1, elapsedMs), recorder.queuedCount());

        assertThat(rejected.get()).as("realistic traces are never rejected").isZero();
        // 33 travel fixes + the 3 slow fixes that confirm the arrival (dwell >= 6 s), then the trip ends
        assertThat(total).isEqualTo((long) SESSIONS * 36);
        assertThat(arrivals).as("every booking arrived").hasSize(SESSIONS);
        assertThat(arrivals.values()).as("exactly once each - no cross-booking interference")
                .allSatisfy(c -> assertThat(c.get()).isEqualTo(1));
        assertThat(geofence.trackedBookings()).as("state is released after arrival").isLessThanOrEqualTo(SESSIONS);
        // sampling: ~1 row per 6 s of travel instead of one per 2-3 s fix
        assertThat(recorder.queuedCount()).isBetween(SESSIONS * 5, SESSIONS * 25);
        // generous ceiling: per-fix CPU work must be negligible next to a 2-12 s upload cadence
        assertThat(elapsedMs).as("40,000 fixes processed").isLessThan(15_000);
    }
}
