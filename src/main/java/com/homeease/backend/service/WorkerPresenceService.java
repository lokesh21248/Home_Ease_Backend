package com.homeease.backend.service;

import com.homeease.backend.model.entity.Worker;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Single source of truth for worker availability ("presence") in Redis.
 *
 * <h3>Model</h3>
 * <ul>
 *   <li><b>Online</b> is explicit: it is the worker's own toggle (Postgres {@code is_online}). A GPS ping never
 *       turns a worker online by itself.</li>
 *   <li><b>Presence</b> is the Redis key {@code worker:heartbeat:<workerId>} with a TTL. It is (re)written by
 *       every accepted GPS ping / location update, so a worker who disconnects disappears when the TTL
 *       lapses, with no cleanup job required.</li>
 *   <li><b>Dispatch eligibility</b> = online + presence key exists (see {@link #isDispatchable(Worker)}).</li>
 *   <li>GEO sets ({@code active_workers_geo}, {@code geo:workers:global}, {@code geo:workers:<zone>}) are plain
 *       sorted sets: members never expire on their own. A low-frequency sweep removes members whose
 *       heartbeat is gone, atomically per member (Lua check-then-remove), so a worker who pings between the
 *       check and the removal is not evicted.</li>
 * </ul>
 *
 * <h3>Policy (one set of values, all env-configurable)</h3>
 * <ul>
 *   <li>{@code WORKER_HEARTBEAT_INTERVAL_SECONDS} (default 15): the cadence workers/clients should ping at and
 *       the cadence at which job-time GPS refreshes the heartbeat ({@code TrackingService} throttle).</li>
 *   <li>{@code WORKER_HEARTBEAT_TTL_SECONDS} (default 60): presence key lifetime = the stale-worker threshold.
 *       Must be at least 3 x interval so two consecutive lost/late heartbeats do not flip a worker offline.</li>
 *   <li>{@code WORKER_PRESENCE_ENFORCE} (default <b>false</b>, opt-in): {@code true} makes dispatch require a live
 *       heartbeat and makes online status explicit. It is OFF by default because it is only safe once the worker
 *       app's real ping cadence has been confirmed to fit the interval/TTL below; with {@code false} the previous
 *       behaviour is kept (no heartbeat gating in dispatch; pings may set online; no sweep).</li>
 *   <li>{@code WORKER_PRESENCE_SWEEP_SECONDS} (default 900, 0 disables): stale GEO member cleanup cadence. Hygiene only: dispatch correctness comes from the heartbeat check, so a long interval is safe and keeps Redis command usage low.</li>
 * </ul>
 * Key names, GEO structures and value formats are unchanged.
 */
@Service
public class WorkerPresenceService {

    private static final Logger logger = LoggerFactory.getLogger(WorkerPresenceService.class);

    public static final String HEARTBEAT_PREFIX = "worker:heartbeat:";
    public static final String LAST_LOCATION_PREFIX = "worker:last_location_time:";
    public static final String GEO_ACTIVE = "active_workers_geo";
    public static final String GEO_GLOBAL = "geo:workers:global";
    public static final String GEO_ZONE_PREFIX = "geo:workers:";

    private static final int SWEEP_BATCH = 100;
    private static final int SWEEP_MAX_MEMBERS_PER_KEY = 20_000;

    /** Atomic per member: remove from the sorted set only if its heartbeat key does not exist right now. */
    public static final String CLEANUP_LUA =
            "local removed = 0\n"
          + "for i = 2, #KEYS do\n"
          + "  if redis.call('EXISTS', KEYS[i]) == 0 then\n"
          + "    removed = removed + redis.call('ZREM', KEYS[1], ARGV[i - 1])\n"
          + "  end\n"
          + "end\n"
          + "return removed";

    private final RedisTemplate<String, String> redis;
    private final int heartbeatIntervalSeconds;
    private final int heartbeatTtlSeconds;
    private final boolean enforce;
    private final int sweepIntervalSeconds;

    /** Also sweep the per-zone sets (geo:workers:&lt;zone&gt;). Default false to bound Redis command usage. */
    @Value("${homeease.worker.presence.sweep-zone-sets:false}")
    private boolean sweepZoneSets = false;

    public void setSweepZoneSets(boolean sweepZoneSets) {
        this.sweepZoneSets = sweepZoneSets;
    }

    // ---- optional stationary-ping optimisation (OFF by default; see tryLightweightRefresh) ----

    @Value("${homeease.worker.presence.stationary.enabled:false}")
    private boolean stationaryEnabled = false;

    @Value("${homeease.worker.presence.stationary.max-move-meters:25}")
    private double stationaryMaxMoveMeters = 25.0;

    @Value("${homeease.worker.presence.stationary.full-refresh-seconds:120}")
    private int stationaryFullRefreshSeconds = 120;

    private static final int MAX_TRACKED_REGISTRATIONS = 20_000;

    /** Last FULL registration (position + time) per worker, per JVM. Bounded LRU; losing it only costs a full ping. */
    private record FullRegistration(double lat, double lng, long atMs) { }

    private final java.util.Map<UUID, FullRegistration> lastFullRegistration = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<UUID, FullRegistration> eldest) {
                    return size() > MAX_TRACKED_REGISTRATIONS;
                }
            });

    @jakarta.annotation.PostConstruct
    void validateStationaryPolicy() {
        configureStationary(stationaryEnabled, stationaryMaxMoveMeters, stationaryFullRefreshSeconds);
    }

    /**
     * Enables/disables the stationary optimisation. When enabled, a full registration happens at least every
     * {@code fullRefreshSeconds}, which must lie between the heartbeat interval and 4 x the heartbeat TTL. Between
     * full registrations {@code worker:last_location_time} may lapse (nothing in this repository reads it).
     */
    public void configureStationary(boolean enabled, double maxMoveMeters, int fullRefreshSeconds) {
        if (enabled) {
            if (maxMoveMeters <= 0) {
                throw new IllegalStateException("WORKER_PING_STATIONARY_METERS must be > 0 (was " + maxMoveMeters + ")");
            }
            if (fullRefreshSeconds < heartbeatIntervalSeconds || fullRefreshSeconds > heartbeatTtlSeconds * 4) {
                throw new IllegalStateException("WORKER_PING_STATIONARY_FULL_REFRESH_SECONDS (" + fullRefreshSeconds
                        + ") must be between WORKER_HEARTBEAT_INTERVAL_SECONDS (" + heartbeatIntervalSeconds
                        + ") and 4 x WORKER_HEARTBEAT_TTL_SECONDS (" + (heartbeatTtlSeconds * 4) + ")");
            }
        }
        this.stationaryEnabled = enabled;
        this.stationaryMaxMoveMeters = maxMoveMeters;
        this.stationaryFullRefreshSeconds = fullRefreshSeconds;
    }

    /**
     * If the optimisation is on, and this worker did a FULL registration less than {@code fullRefreshSeconds} ago
     * and has since moved no more than {@code maxMoveMeters}, refreshes ONLY the heartbeat key with ONE command
     * (instead of 7: fraud GET+SET, 3 x GEOADD, 2 x SET) and returns true. Otherwise returns false and the caller
     * runs the normal full path. The GEO position can therefore lag by at most {@code maxMoveMeters} and
     * {@code fullRefreshSeconds}, which is irrelevant against a 5 km dispatch radius.
     *
     * @throws org.springframework.dao.DataAccessException if Redis fails (callers fall back to the full path)
     */
    public boolean tryLightweightRefresh(UUID workerId, double lat, double lng, String heartbeatValue) {
        if (!stationaryEnabled) {
            return false;
        }
        FullRegistration last = lastFullRegistration.get(workerId);
        if (last == null || System.currentTimeMillis() - last.atMs() >= stationaryFullRefreshSeconds * 1000L) {
            return false;
        }
        if (distanceMeters(last.lat(), last.lng(), lat, lng) > stationaryMaxMoveMeters) {
            return false;
        }
        redis.opsForValue().set(HEARTBEAT_PREFIX + workerId, heartbeatValue != null ? heartbeatValue : "ONLINE", heartbeatTtl());
        return true;
    }

    /** Remembers a full registration (position and time). Package-private so tests can seed it without GEOADD. */
    void recordFullRegistration(UUID workerId, double lat, double lng) {
        lastFullRegistration.put(workerId, new FullRegistration(lat, lng, System.currentTimeMillis()));
    }

    private static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6_371_000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public WorkerPresenceService(
            RedisTemplate<String, String> redis,
            @Value("${homeease.worker.presence.heartbeat-interval-seconds:15}") int heartbeatIntervalSeconds,
            @Value("${homeease.worker.presence.heartbeat-ttl-seconds:60}") int heartbeatTtlSeconds,
            @Value("${homeease.worker.presence.enforce:false}") boolean enforce,
            @Value("${homeease.worker.presence.sweep-interval-seconds:900}") int sweepIntervalSeconds) {
        if (heartbeatIntervalSeconds <= 0) {
            throw new IllegalStateException("WORKER_HEARTBEAT_INTERVAL_SECONDS must be > 0 (was " + heartbeatIntervalSeconds + ")");
        }
        if (heartbeatTtlSeconds < 3 * heartbeatIntervalSeconds) {
            throw new IllegalStateException("WORKER_HEARTBEAT_TTL_SECONDS (" + heartbeatTtlSeconds
                    + ") must be >= 3 x WORKER_HEARTBEAT_INTERVAL_SECONDS (" + heartbeatIntervalSeconds
                    + ") so that two late heartbeats do not mark a healthy worker offline");
        }
        this.redis = redis;
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
        this.heartbeatTtlSeconds = heartbeatTtlSeconds;
        this.enforce = enforce;
        this.sweepIntervalSeconds = sweepIntervalSeconds;
    }

    public int heartbeatIntervalSeconds() { return heartbeatIntervalSeconds; }

    public int heartbeatTtlSeconds() { return heartbeatTtlSeconds; }

    public boolean isEnforced() { return enforce; }

    public Duration heartbeatTtl() { return Duration.ofSeconds(heartbeatTtlSeconds); }

    // ---------------------------------------------------------------- writes

    /**
     * Registers/refreshes a worker as present at the given (fresh) coordinates: GEO members plus heartbeat
     * and last-location timestamp, in ONE pipelined round trip (matters on a remote Redis such as Upstash).
     *
     * @param heartbeatValue the heartbeat value (kept compatible with previous producers: the zone,
     *                       "ONLINE" or "ALIVE"); consumers only test for key existence
     */
    public void registerPresence(UUID workerId, double lat, double lng, String zone, String heartbeatValue) {
        final String id = workerId.toString();
        final Point point = new Point(lng, lat);
        final String targetZone = (zone != null && !zone.isBlank()) ? zone.trim() : null;
        final String value = heartbeatValue != null ? heartbeatValue : (targetZone != null ? targetZone : "ONLINE");
        final String nowMs = String.valueOf(System.currentTimeMillis());
        final Duration ttl = heartbeatTtl();
        redis.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings({"unchecked", "rawtypes"})
            public Object execute(RedisOperations ops) throws DataAccessException {
                if (targetZone != null) {
                    ops.opsForGeo().add(GEO_ZONE_PREFIX + targetZone, point, id);
                }
                ops.opsForGeo().add(GEO_GLOBAL, point, id);
                ops.opsForGeo().add(GEO_ACTIVE, point, id);
                ops.opsForValue().set(HEARTBEAT_PREFIX + id, value, ttl);
                ops.opsForValue().set(LAST_LOCATION_PREFIX + id, nowMs, ttl);
                return null;
            }
        });
        recordFullRegistration(workerId, lat, lng); // only after the pipeline succeeded
    }

    /** Refreshes only the heartbeat (no coordinates). */
    public void refreshHeartbeat(UUID workerId, String value) {
        final String id = workerId.toString();
        final String nowMs = String.valueOf(System.currentTimeMillis());
        final Duration ttl = heartbeatTtl();
        redis.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings({"unchecked", "rawtypes"})
            public Object execute(RedisOperations ops) throws DataAccessException {
                ops.opsForValue().set(HEARTBEAT_PREFIX + id, value != null ? value : "ONLINE", ttl);
                ops.opsForValue().set(LAST_LOCATION_PREFIX + id, nowMs, ttl);
                return null;
            }
        });
    }

    /** Explicit offline: leaves every GEO set (including its zone set) and drops the heartbeat keys. */
    public void markOffline(UUID workerId) {
        lastFullRegistration.remove(workerId); // an offline worker must never be refreshed by the lightweight path
        final String id = workerId.toString();
        String zoneFromHeartbeat = null;
        try {
            String hb = redis.opsForValue().get(HEARTBEAT_PREFIX + id);
            if (hb != null && !hb.isBlank() && !"ONLINE".equals(hb) && !"ALIVE".equals(hb)) {
                zoneFromHeartbeat = hb;
            }
        } catch (Exception ignored) {
            // proceed with the unconditional removals below
        }
        final String zone = zoneFromHeartbeat;
        redis.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings({"unchecked", "rawtypes"})
            public Object execute(RedisOperations ops) throws DataAccessException {
                ops.opsForGeo().remove(GEO_ACTIVE, id);
                ops.opsForGeo().remove(GEO_GLOBAL, id);
                if (zone != null) {
                    ops.opsForGeo().remove(GEO_ZONE_PREFIX + zone, id);
                }
                ops.delete(HEARTBEAT_PREFIX + id);
                ops.delete(LAST_LOCATION_PREFIX + id);
                return null;
            }
        });
    }

    // ----------------------------------------------------------------- reads

    /** True if the heartbeat key exists. Throws if Redis is unreachable. */
    public boolean isAlive(UUID workerId) {
        return Boolean.TRUE.equals(redis.hasKey(HEARTBEAT_PREFIX + workerId));
    }

    /** Subset of {@code workerIds} that currently have a heartbeat, using ONE MGET regardless of size. */
    public Set<UUID> retainAlive(Collection<UUID> workerIds) {
        Set<UUID> alive = new LinkedHashSet<>();
        if (workerIds == null || workerIds.isEmpty()) {
            return alive;
        }
        List<UUID> ordered = new ArrayList<>(new LinkedHashSet<>(workerIds));
        List<String> keys = new ArrayList<>(ordered.size());
        for (UUID id : ordered) {
            keys.add(HEARTBEAT_PREFIX + id);
        }
        List<String> values = redis.opsForValue().multiGet(keys);
        if (values == null) {
            return alive;
        }
        for (int i = 0; i < ordered.size() && i < values.size(); i++) {
            if (values.get(i) != null) {
                alive.add(ordered.get(i));
            }
        }
        return alive;
    }

    /**
     * Dispatch-time gate. With enforcement on, a worker must have a live heartbeat. If Redis itself is
     * unreachable, fall back to the Postgres {@code last_location_updated_at} using the SAME threshold, so
     * a Redis outage neither dispatches to long-gone workers nor blocks everyone permanently.
     */
    public boolean isDispatchable(Worker worker) {
        if (!enforce) {
            return true;
        }
        if (worker == null || worker.getWorkerId() == null) {
            return false;
        }
        try {
            return isAlive(worker.getWorkerId());
        } catch (Exception redisUnavailable) {
            Instant last = worker.getLastLocationUpdatedAt();
            boolean fresh = last != null && last.isAfter(Instant.now().minusSeconds(heartbeatTtlSeconds));
            logger.warn("[presence] Redis unavailable ({}); falling back to DB last_location_updated_at for worker {} -> {}",
                    redisUnavailable.getClass().getSimpleName(), worker.getWorkerId(), fresh ? "fresh" : "stale");
            return fresh;
        }
    }

    // ----------------------------------------------------------------- sweep

    @Scheduled(
            // A configured 0 means "off". A literal 0 ms fixed delay would make Spring re-run the task back to
            // back, so 0 (or a negative value) is mapped to an hourly tick whose body returns immediately.
            initialDelayString = "#{${homeease.worker.presence.sweep-interval-seconds:900} > 0 ? ${homeease.worker.presence.sweep-interval-seconds:900} * 1000 : 3600000}",
            fixedDelayString = "#{${homeease.worker.presence.sweep-interval-seconds:900} > 0 ? ${homeease.worker.presence.sweep-interval-seconds:900} * 1000 : 3600000}")
    public void scheduledSweep() {
        if (!enforce || sweepIntervalSeconds <= 0) {
            return;
        }
        try {
            int removed = sweepStaleMembers();
            if (removed > 0) {
                logger.info("[presence] sweep removed {} stale GEO member(s)", removed);
            }
        } catch (Exception e) {
            logger.warn("[presence] sweep skipped: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Removes GEO members that have no heartbeat. Never deletes keys, only members that are provably
     * stale at the moment of removal (Lua re-checks EXISTS inside the same atomic step).
     *
     * @return number of members removed
     */
    public int sweepStaleMembers() {
        Set<String> geoKeys = new LinkedHashSet<>(List.of(GEO_ACTIVE, GEO_GLOBAL));
        if (sweepZoneSets) {
            // Per-zone sets (geo:workers:<pincode>) scale with the number of pincodes, so each extra set adds
            // Redis commands every sweep. Off by default: they are only read behind the heartbeat filter.
            ScanOptions options = ScanOptions.scanOptions().match(GEO_ZONE_PREFIX + "*").count(100).build();
            try (Cursor<String> cursor = redis.scan(options)) {
                while (cursor.hasNext()) {
                    geoKeys.add(cursor.next());
                }
            }
        }
        int removedTotal = 0;
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(CLEANUP_LUA, Long.class);
        for (String geoKey : geoKeys) {
            Long card = redis.opsForZSet().zCard(geoKey);
            if (card == null || card == 0) {
                continue;
            }
            Set<String> members = redis.opsForZSet().range(geoKey, 0, SWEEP_MAX_MEMBERS_PER_KEY - 1L);
            if (members == null || members.isEmpty()) {
                continue;
            }
            if (card > SWEEP_MAX_MEMBERS_PER_KEY) {
                logger.warn("[presence] {} has {} members; sweeping only the first {}", geoKey, card, SWEEP_MAX_MEMBERS_PER_KEY);
            }
            List<String> batch = new ArrayList<>(SWEEP_BATCH);
            for (String member : members) {
                batch.add(member);
                if (batch.size() == SWEEP_BATCH) {
                    removedTotal += runCleanup(script, geoKey, batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                removedTotal += runCleanup(script, geoKey, batch);
            }
        }
        return removedTotal;
    }

    private int runCleanup(DefaultRedisScript<Long> script, String geoKey, List<String> members) {
        List<String> keys = new ArrayList<>(members.size() + 1);
        keys.add(geoKey);
        for (String m : members) {
            keys.add(HEARTBEAT_PREFIX + m);
        }
        Long removed = redis.execute(script, keys, members.toArray());
        return removed == null ? 0 : removed.intValue();
    }

    /** Valid, non-(0,0) coordinates only; a worker must supply a FRESH fix to become discoverable. */
    public static boolean isUsableCoordinate(Double lat, Double lng) {
        return lat != null && lng != null
                && !lat.isNaN() && !lng.isNaN() && !lat.isInfinite() && !lng.isInfinite()
                && Math.abs(lat) <= 90.0 && Math.abs(lng) <= 180.0
                && !(lat == 0.0 && lng == 0.0);
    }
}
