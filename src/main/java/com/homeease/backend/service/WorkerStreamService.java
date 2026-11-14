package com.homeease.backend.service;

import com.homeease.backend.model.entity.Worker;
import com.homeease.backend.repository.WorkerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class WorkerStreamService {

    private static final Logger logger = LoggerFactory.getLogger(WorkerStreamService.class);
    /**
     * @deprecated the heartbeat TTL is now one configurable policy value:
     * {@link WorkerPresenceService#heartbeatTtl()} ({@code WORKER_HEARTBEAT_TTL_SECONDS}, default 60s).
     * Retained only so existing references still compile; it is no longer used for writes.
     */
    @Deprecated
    public static final Duration HEARTBEAT_TTL = Duration.ofSeconds(30);

    private final RedisTemplate<String, String> redisTemplate;
    private final WorkerRepository workerRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final FraudDetectionService fraudDetectionService;
    private final WorkerPresenceService presenceService;

    public WorkerStreamService(RedisTemplate<String, String> redisTemplate,
                               WorkerRepository workerRepository,
                               SimpMessagingTemplate messagingTemplate,
                               FraudDetectionService fraudDetectionService,
                               WorkerPresenceService presenceService) {
        this.redisTemplate = redisTemplate;
        this.workerRepository = workerRepository;
        this.messagingTemplate = messagingTemplate;
        this.fraudDetectionService = fraudDetectionService;
        this.presenceService = presenceService;
    }

    /** Heartbeat TTL (seconds) the clients should plan around; reported back in the ping response. */
    public int heartbeatTtlSeconds() {
        return presenceService.heartbeatTtlSeconds();
    }

    /** Interval (seconds) at which clients should ping. */
    public int heartbeatIntervalSeconds() {
        return presenceService.heartbeatIntervalSeconds();
    }

    /**
     * Records a real-time worker ping from /worker/stream:
     * 1. Ignores pings from workers that are not explicitly online, verified and unblocked (a stray late ping
     *    after "go offline" must not make a worker discoverable again; online status is explicit).
     * 2. Validates telemetry against GPS spoofing and velocity anomalies.
     * 3. Registers presence in ONE pipelined round trip: GEO sets plus the heartbeat key
     *    worker:heartbeat:{workerId} with the configured TTL (default 60s).
     * 4. Updates the Postgres worker location (it no longer flips is_online to true).
     *
     * @return true if presence was registered, false if the ping was ignored or rejected
     */
    // Deliberately NOT @Transactional: this method makes 2-3 synchronous Redis round trips (fraud check, presence
    // pipeline), each of which can take up to the 2 s command timeout when the remote Redis is slow. Inside a
    // transaction that would pin a Hikari connection (pool of 20) for the whole call, so a Redis brownout could
    // starve every other endpoint of database connections. The two database touches (read worker, save worker) are
    // each their own short repository transaction.
    public boolean recordWorkerPing(UUID workerId, double lat, double lng, String zone, Double accuracy) {
        if (workerId == null || !WorkerPresenceService.isUsableCoordinate(lat, lng)) {
            return false;
        }

        // Resolve canonical worker whether workerId is worker_id (PK) or user_id (FK)
        Worker worker = workerRepository.findById(workerId)
                .or(() -> workerRepository.findByUser_UserId(workerId))
                .orElse(null);

        if (presenceService.isEnforced()) {
            boolean explicitlyOnline = worker != null
                    && Boolean.TRUE.equals(worker.getIsOnline())
                    && Boolean.TRUE.equals(worker.getIsVerified())
                    && (worker.getBlockedUntil() == null || !worker.getBlockedUntil().isAfter(Instant.now()));
            if (!explicitlyOnline) {
                logger.debug("Ping ignored: worker {} is unknown, offline, unverified or blocked", workerId);
                return false;
            }
        }

        UUID canonicalWorkerId = worker != null ? worker.getWorkerId() : workerId;
        String targetZone = (zone != null && !zone.isBlank()) ? zone.trim() : null;
        String heartbeatValue = targetZone != null ? targetZone : "ONLINE";

        // Optional stationary optimisation (WORKER_PING_STATIONARY_ENABLED, default OFF): a worker who has not
        // moved since a recent full registration only refreshes the heartbeat (ONE Redis command instead of 7).
        // The fraud check is skipped for these pings because the position is within a few metres of one that
        // already passed it. Any Redis error here falls through to the normal full path below.
        try {
            if (presenceService.tryLightweightRefresh(canonicalWorkerId, lat, lng, heartbeatValue)) {
                updateWorkerRecord(worker, lat, lng);
                return true;
            }
        } catch (Exception e) {
            logger.debug("Lightweight heartbeat refresh failed for worker {} ({}); using the full path",
                    canonicalWorkerId, e.getClass().getSimpleName());
        }

        // Phase 5 Fraud hardening: Anti-GPS spoofing check
        double acc = accuracy != null ? accuracy : 10.0;
        FraudDetectionService.TelemetryValidationResult validation =
                fraudDetectionService.validateWorkerLocation(canonicalWorkerId, lat, lng, acc, System.currentTimeMillis(), zone);

        if (!validation.isValid()) {
            logger.warn("Worker {} ping rejected by fraud filter: {}", canonicalWorkerId, validation.getReason());
            return false;
        }

        // GEO sets (geo:workers:{zone}, geo:workers:global, active_workers_geo) + heartbeat + last-location
        // timestamp, all with the configured TTL, in a single pipelined round trip.
        boolean registered = false;
        try {
            presenceService.registerPresence(canonicalWorkerId, lat, lng, targetZone, heartbeatValue);
            registered = true;
        } catch (Exception e) {
            logger.warn("Redis presence registration failed for worker {}: {}", canonicalWorkerId, e.getClass().getSimpleName());
        }

        updateWorkerRecord(worker, lat, lng);
        return registered;
    }

    /** Updates the Postgres worker record (location only; online status stays explicit). */
    private void updateWorkerRecord(Worker worker, double lat, double lng) {
        try {
            if (worker != null) {
                worker.setCurrentLat(lat);
                worker.setCurrentLng(lng);
                if (!presenceService.isEnforced()) {
                    worker.setIsOnline(true); // legacy behaviour, only with WORKER_PRESENCE_ENFORCE=false
                }
                worker.setLastLocationUpdatedAt(Instant.now());
                workerRepository.save(worker);
            }
        } catch (Exception e) {
            logger.debug("Worker DB location update skipped: {}", e.getMessage());
        }
    }

    /**
     * Finds active nearby workers from the Redis GEO sets, keeping only those with a live heartbeat
     * (one MGET for all candidates; TTL = {@code WORKER_HEARTBEAT_TTL_SECONDS}).
     */
    public List<UUID> findActiveWorkersInZone(String zone, double lat, double lng, double radiusMeters) {
        double radiusKm = radiusMeters / 1000.0;
        List<UUID> activeWorkers = new ArrayList<>();

        try {
            Circle searchArea = new Circle(new Point(lng, lat), new Distance(radiusKm, Metrics.KILOMETERS));
            GeoResults<RedisGeoCommands.GeoLocation<String>> results = null;
            if (zone != null && !zone.isBlank()) {
                results = redisTemplate.opsForGeo().radius("geo:workers:" + zone.trim(), searchArea);
            }
            if (results == null || results.getContent().isEmpty()) {
                results = redisTemplate.opsForGeo().radius("active_workers_geo", searchArea);
            }
            if (results == null || results.getContent().isEmpty()) {
                results = redisTemplate.opsForGeo().radius("geo:workers:global", searchArea);
            }

            if (results != null && !results.getContent().isEmpty()) {
                Set<UUID> candidates = new LinkedHashSet<>();
                for (var res : results.getContent()) {
                    try {
                        candidates.add(UUID.fromString(res.getContent().getName()));
                    } catch (IllegalArgumentException notAWorkerId) {
                        // ignore foreign members
                    }
                }
                activeWorkers.addAll(presenceService.retainAlive(candidates));
            }
        } catch (Exception e) {
            logger.warn("Redis GEO search failed for zone {}: {}", zone, e.getClass().getSimpleName());
        }

        return activeWorkers;
    }

    /**
     * Broadcasts job offer directly over worker WebSocket stream (/worker/stream).
     */
    public void pushOfferToWorkerStream(UUID workerId, Map<String, Object> offerPayload) {
        try {
            messagingTemplate.convertAndSend("/topic/worker/" + workerId + "/stream", offerPayload);
            messagingTemplate.convertAndSend("/topic/worker/" + workerId + "/requests", offerPayload);
        } catch (Exception e) {
            logger.warn("Failed to push offer to worker stream {}: {}", workerId, e.getMessage());
        }
    }
}

