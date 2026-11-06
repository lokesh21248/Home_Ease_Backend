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

        // Phase 5 Fraud hardening: Anti-GPS spoofing check
        double acc = accuracy != null ? accuracy : 10.0;
        FraudDetectionService.TelemetryValidationResult validation =
                fraudDetectionService.validateWorkerLocation(workerId, lat, lng, acc, System.currentTimeMillis(), zone);

        if (!validation.isValid()) {
            logger.warn("Worker {} ping rejected by fraud filter: {}", workerId, validation.getReason());
            return;
        }

        String targetZone = (zone != null && !zone.isBlank()) ? zone.trim() : "500072";
        String geoKey = "geo:workers:" + targetZone;
        String heartbeatKey = "worker:heartbeat:" + workerId;

        // 1. Redis GEO Set: geo:workers:{zone}
        try {
            redisTemplate.opsForGeo().add(geoKey, new Point(lng, lat), workerId.toString());
            // Also add to global fallback set
            redisTemplate.opsForGeo().add("geo:workers:global", new Point(lng, lat), workerId.toString());
        } catch (Exception e) {
            logger.warn("Redis GEOADD error for worker {}: {}", workerId, e.getMessage());
        }

        // 2. Redis 30-second TTL heartbeat
        try {
            redisTemplate.opsForValue().set(heartbeatKey, targetZone, HEARTBEAT_TTL);
        } catch (Exception e) {
            logger.warn("Redis heartbeat set error for worker {}: {}", workerId, e.getMessage());
        }

        // 3. Update Worker Postgres record
        try {
            workerRepository.findById(workerId).ifPresent(w -> {
                w.setCurrentLat(lat);
                w.setCurrentLng(lng);
                w.setIsOnline(true);
                w.setLastLocationUpdatedAt(Instant.now());
                workerRepository.save(w);
            });
        } catch (Exception e) {
            logger.debug("Worker DB location update skipped: {}", e.getMessage());
        }
    }

    /**
     * Finds active nearby workers from Redis GEO set geo:workers:{zone}
     * filtering strictly by valid 30-second heartbeats.
     */
    public List<UUID> findActiveWorkersInZone(String zone, double lat, double lng, double radiusMeters) {
        String targetZone = (zone != null && !zone.isBlank()) ? zone.trim() : "500072";
        String geoKey = "geo:workers:" + targetZone;
        double radiusKm = radiusMeters / 1000.0;

        List<UUID> activeWorkers = new ArrayList<>();

        try {
            Circle searchArea = new Circle(new Point(lng, lat), new Distance(radiusKm, Metrics.KILOMETERS));
            GeoResults<RedisGeoCommands.GeoLocation<String>> results = redisTemplate.opsForGeo().radius(geoKey, searchArea);

            if (results != null && !results.getContent().isEmpty()) {
                for (var res : results.getContent()) {
                    String workerIdStr = res.getContent().getName();
                    // Check if 30-second heartbeat is still active
                    Boolean hasHeartbeat = redisTemplate.hasKey("worker:heartbeat:" + workerIdStr);
                    if (Boolean.TRUE.equals(hasHeartbeat)) {
                        activeWorkers.add(UUID.fromString(workerIdStr));
                    } else {
                        // Heartbeat expired: prune from GEO set
                        redisTemplate.opsForGeo().remove(geoKey, workerIdStr);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Redis GEO search failed for zone {}: {}", targetZone, e.getMessage());
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

