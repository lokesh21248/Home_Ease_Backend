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
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class WorkerStreamService {

    private static final Logger logger = LoggerFactory.getLogger(WorkerStreamService.class);
    public static final Duration HEARTBEAT_TTL = Duration.ofSeconds(30);

    private final RedisTemplate<String, String> redisTemplate;
    private final WorkerRepository workerRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final FraudDetectionService fraudDetectionService;

    public WorkerStreamService(RedisTemplate<String, String> redisTemplate,
                               WorkerRepository workerRepository,
                               SimpMessagingTemplate messagingTemplate,
                               FraudDetectionService fraudDetectionService) {
        this.redisTemplate = redisTemplate;
        this.workerRepository = workerRepository;
        this.messagingTemplate = messagingTemplate;
        this.fraudDetectionService = fraudDetectionService;
    }

    /**
     * Records real-time worker ping from /worker/stream:
     * 1. Validates telemetry against GPS spoofing and velocity anomalies (Phase 5).
     * 2. Adds to Redis GEO set geo:workers:{zone} (Point: lng, lat).
     * 3. Sets 30-second TTL heartbeat key in Redis: worker:heartbeat:{workerId}.
     * 4. Updates Postgres worker location & is_online = true.
     */
    @Transactional
    public void recordWorkerPing(UUID workerId, double lat, double lng, String zone, Double accuracy) {
        if (workerId == null || Double.isNaN(lat) || Double.isNaN(lng)) {
            return;
        }

        // Resolve canonical worker whether workerId is worker_id (PK) or user_id (FK)
        Worker worker = workerRepository.findById(workerId)
                .or(() -> workerRepository.findByUser_UserId(workerId))
                .orElse(null);

        UUID canonicalWorkerId = worker != null ? worker.getWorkerId() : workerId;

        // Phase 5 Fraud hardening: Anti-GPS spoofing check
        double acc = accuracy != null ? accuracy : 10.0;
        FraudDetectionService.TelemetryValidationResult validation =
                fraudDetectionService.validateWorkerLocation(canonicalWorkerId, lat, lng, acc, System.currentTimeMillis(), zone);

        if (!validation.isValid()) {
            logger.warn("Worker {} ping rejected by fraud filter: {}", canonicalWorkerId, validation.getReason());
            return;
        }

        String targetZone = (zone != null && !zone.isBlank()) ? zone.trim() : null;
        String heartbeatKey = "worker:heartbeat:" + canonicalWorkerId;
        String wIdStr = canonicalWorkerId.toString();

        // 1. Redis GEO Sets: geo:workers:{zone} (if zone provided), geo:workers:global, AND active_workers_geo
        try {
            Point pt = new Point(lng, lat);
            if (targetZone != null) {
                redisTemplate.opsForGeo().add("geo:workers:" + targetZone, pt, wIdStr);
            }
            redisTemplate.opsForGeo().add("geo:workers:global", pt, wIdStr);
            redisTemplate.opsForGeo().add("active_workers_geo", pt, wIdStr);
        } catch (Exception e) {
            logger.warn("Redis GEOADD error for worker {}: {}", canonicalWorkerId, e.getMessage());
        }

        // 2. Redis 30-second TTL heartbeat and location timestamp
        try {
            redisTemplate.opsForValue().set(heartbeatKey, targetZone != null ? targetZone : "ONLINE", HEARTBEAT_TTL);
            redisTemplate.opsForValue().set("worker:last_location_time:" + canonicalWorkerId, String.valueOf(System.currentTimeMillis()), HEARTBEAT_TTL);
        } catch (Exception e) {
            logger.warn("Redis heartbeat set error for worker {}: {}", canonicalWorkerId, e.getMessage());
        }

        // 3. Update Worker Postgres record
        try {
            if (worker != null) {
                worker.setCurrentLat(lat);
                worker.setCurrentLng(lng);
                worker.setIsOnline(true);
                worker.setLastLocationUpdatedAt(Instant.now());
                workerRepository.save(worker);
            }
        } catch (Exception e) {
            logger.debug("Worker DB location update skipped: {}", e.getMessage());
        }
    }

    /**
     * Finds active nearby workers from Redis GEO set geo:workers:{zone}
     * filtering strictly by valid 30-second heartbeats.
     */
    public List<UUID> findActiveWorkersInZone(String zone, double lat, double lng, double radiusMeters) {
        double radiusKm = radiusMeters / 1000.0;
        List<UUID> activeWorkers = new ArrayList<>();
        Set<String> processedWorkerIds = new HashSet<>();

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
                for (var res : results.getContent()) {
                    String workerIdStr = res.getContent().getName();
                    if (!processedWorkerIds.add(workerIdStr)) continue;

                    // Check if 30-second heartbeat is still active
                    Boolean hasHeartbeat = redisTemplate.hasKey("worker:heartbeat:" + workerIdStr);
                    if (Boolean.TRUE.equals(hasHeartbeat)) {
                        activeWorkers.add(UUID.fromString(workerIdStr));
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Redis GEO search failed for zone {}: {}", zone, e.getMessage());
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

