package com.homeease.backend.tracking;

import com.homeease.backend.routing.GeoMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes the durable PostgreSQL breadcrumb trail ({@code booking_location_history}) from accepted GPS fixes.
 *
 * <p>Sampling: a fix is kept when it is the booking's first, or {@code min-interval} has passed, or the partner moved
 * {@code min-distance} (and at least 5 s passed). With a 3 s GPS cadence that is roughly 4 rows per booking-minute
 * instead of 20. Rows are buffered in a bounded queue and inserted with one JDBC batch every few seconds, so GPS
 * ingestion never waits for the database. Each insert is conditional on the booking existing, so an unknown id can
 * never fail the whole batch on the foreign key.</p>
 */
@Component
public class LocationHistoryRecorder {

    private static final Logger log = LoggerFactory.getLogger(LocationHistoryRecorder.class);
    static final String INSERT_SQL = "INSERT INTO booking_location_history "
            + "(booking_id, worker_id, lat, lng, accuracy_m, speed_mps, heading_deg, recorded_at, received_at) "
            + "SELECT ?, ?, ?, ?, ?, ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM bookings b WHERE b.booking_id = ?)";
    private static final int MAX_TRACKED = 5_000;

    record Pending(UUID bookingId, UUID workerId, GpsFix fix, long receivedAtMs) {
    }

    private record Last(long ts, double lat, double lng) {
    }

    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final long minIntervalMs;
    private final double minDistanceM;
    private final int maxQueue;
    private final int batchSize;
    private final int retentionDays;

    private final ConcurrentLinkedQueue<Pending> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final Map<UUID, Last> lastKept = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Last> eldest) {
            return size() > MAX_TRACKED;
        }
    });

    public LocationHistoryRecorder(JdbcTemplate jdbc,
                                   @Value("${homeease.tracking.history.enabled:true}") boolean enabled,
                                   @Value("${homeease.tracking.history.min-interval-seconds:15}") long minIntervalSeconds,
                                   @Value("${homeease.tracking.history.min-distance-meters:30}") double minDistanceM,
                                   @Value("${homeease.tracking.history.max-queue:20000}") int maxQueue,
                                   @Value("${homeease.tracking.history.batch-size:500}") int batchSize,
                                   @Value("${homeease.tracking.history.retention-days:30}") int retentionDays) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.minIntervalMs = minIntervalSeconds * 1000L;
        this.minDistanceM = minDistanceM;
        this.maxQueue = maxQueue;
        this.batchSize = batchSize;
        this.retentionDays = retentionDays;
    }

    /** @return true when the fix was sampled into the write queue */
    public boolean offer(UUID bookingId, UUID workerId, GpsFix fix, long receivedAtMs) {
        if (!enabled || bookingId == null || fix == null) {
            return false;
        }
        boolean keep;
        synchronized (lastKept) {
            Last last = lastKept.get(bookingId);
            if (last == null) {
                keep = true;
            } else if (fix.timestampMs() <= last.ts()) {
                keep = false; // replay / out of order
            } else {
                long dt = fix.timestampMs() - last.ts();
                double moved = GeoMath.haversineMeters(last.lat(), last.lng(), fix.lat(), fix.lng());
                keep = dt >= minIntervalMs || (moved >= minDistanceM && dt >= 5_000L);
            }
            if (keep) {
                lastKept.put(bookingId, new Last(fix.timestampMs(), fix.lat(), fix.lng()));
            }
        }
        if (!keep) {
            return false;
        }
        if (queued.get() >= maxQueue) {
            dropped.incrementAndGet();
            return false;
        }
        queue.add(new Pending(bookingId, workerId, fix, receivedAtMs));
        queued.incrementAndGet();
        return true;
    }

    /** Drains the buffer in JDBC batches. Runs on the scheduler; also callable directly (tests, shutdown). */
    @Scheduled(fixedDelayString = "${homeease.tracking.history.flush-interval-ms:5000}")
    public int flush() {
        int total = 0;
        while (true) {
            List<Pending> batch = new ArrayList<>(batchSize);
            Pending p;
            while (batch.size() < batchSize && (p = queue.poll()) != null) {
                queued.decrementAndGet();
                batch.add(p);
            }
            if (batch.isEmpty()) {
                return total;
            }
            try {
                jdbc.batchUpdate(INSERT_SQL, batch, batch.size(), (ps, row) -> {
                    GpsFix f = row.fix();
                    ps.setObject(1, row.bookingId());
                    ps.setObject(2, row.workerId());
                    ps.setDouble(3, f.lat());
                    ps.setDouble(4, f.lng());
                    ps.setObject(5, f.accuracyM() == null ? null : f.accuracyM().floatValue());
                    ps.setObject(6, f.speedMps() == null ? null : f.speedMps().floatValue());
                    ps.setObject(7, f.headingDeg() == null ? null : f.headingDeg().floatValue());
                    ps.setTimestamp(8, Timestamp.from(Instant.ofEpochMilli(f.timestampMs())));
                    ps.setTimestamp(9, Timestamp.from(Instant.ofEpochMilli(row.receivedAtMs())));
                    ps.setObject(10, row.bookingId());
                });
                written.addAndGet(batch.size());
                total += batch.size();
            } catch (RuntimeException e) {
                dropped.addAndGet(batch.size());
                log.warn("Dropped {} tracking-history rows after a database error: {}", batch.size(),
                        e.getClass().getSimpleName());
                return total;
            }
        }
    }

    /** Daily retention purge (default 30 days). */
    @Scheduled(cron = "${homeease.tracking.history.purge-cron:0 40 3 * * *}")
    public int purgeExpired() {
        if (!enabled || retentionDays <= 0) {
            return 0;
        }
        try {
            int n = jdbc.update("DELETE FROM booking_location_history WHERE received_at < ?",
                    Timestamp.from(Instant.now().minusSeconds(retentionDays * 86_400L)));
            if (n > 0) {
                log.info("Purged {} tracking-history rows older than {} days", n, retentionDays);
            }
            return n;
        } catch (RuntimeException e) {
            log.warn("Tracking-history purge failed: {}", e.getClass().getSimpleName());
            return 0;
        }
    }

    public void forget(UUID bookingId) {
        lastKept.remove(bookingId);
    }

    public long droppedCount() {
        return dropped.get();
    }

    public long writtenCount() {
        return written.get();
    }

    public int queuedCount() {
        return queued.get();
    }
}
