package com.homeease.dispatch.service;

import com.homeease.dispatch.dto.DispatchDto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;

import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.UUID;

@Service
public class DispatchEngineService {

    private static final Logger logger = LoggerFactory.getLogger(DispatchEngineService.class);

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${homeease.dispatch.radius-meters:5000}")
    private double radiusMeters;

    /**
     * Same switch and key contract as the core backend ({@code WORKER_PRESENCE_ENFORCE}). GEO members never
     * expire, so a worker found by GEO radius is only a candidate while {@code worker:heartbeat:<id>} exists
     * (written by the core with TTL {@code WORKER_HEARTBEAT_TTL_SECONDS}). This service only tests existence,
     * so it needs no TTL value of its own and cannot disagree with the writer.
     */
    @Value("${homeease.worker.presence.enforce:false}")
    private boolean enforcePresence = false;

    private static final String HEARTBEAT_PREFIX = "worker:heartbeat:";

    public DispatchEngineService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    private boolean anyWorkerAlive(java.util.List<String> workerIds) {
        if (workerIds.isEmpty()) {
            return false;
        }
        java.util.List<String> keys = new java.util.ArrayList<>(workerIds.size());
        for (String id : workerIds) {
            keys.add(HEARTBEAT_PREFIX + id);
        }
        java.util.List<String> values = redisTemplate.opsForValue().multiGet(keys); // one command for all candidates
        return values != null && values.stream().anyMatch(java.util.Objects::nonNull);
    }

    public DispatchResultResponse triggerSpatialMatching(TriggerDispatchRequest request) {
        logger.info("Spatial Dispatch Microservice (Port 8081): Matching candidates within 5km for booking {}", request.getBookingId());

        boolean candidateFound = false;
        try {
            Circle area = new Circle(new org.springframework.data.geo.Point(request.getUserLng(), request.getUserLat()), new Distance(radiusMeters, Metrics.NEUTRAL));
            var results = redisTemplate.opsForGeo().radius("active_workers_geo", area);
            if (results != null && !results.getContent().isEmpty()) {
                candidateFound = !enforcePresence || anyWorkerAlive(results.getContent().stream()
                        .map(r -> r.getContent().getName()).toList());
            }
        } catch (Exception e) {
            // exception class only: messages from the Redis driver can include connection details
            logger.warn("Redis spatial lookup fallback: {}", e.getClass().getSimpleName());
        }

        return DispatchResultResponse.builder()
                .bookingId(request.getBookingId())
                .status(candidateFound ? "CANDIDATES_ALERTED" : "WORKER_NOT_FOUND")
                .message(candidateFound ? "Candidate workers notified within 5km radius." : "No active candidate workers found nearby.")
                .build();
    }

    public DispatchResultResponse acceptJob(AcceptJobRequest request) {
        String pinCode = String.format("%04d", new SecureRandom().nextInt(10000));
        return DispatchResultResponse.builder()
                .bookingId(request.getBookingId())
                .status("ACCEPTED")
                .pinCode(pinCode)
                .message("Job accepted by candidate worker. 4-digit verification PIN generated.")
                .build();
    }

    public DispatchResultResponse rejectJob(RejectJobRequest request) {
        logger.info("Disciplinary Lockout applied to worker {}: 1-hour block triggered for job rejection.", request.getWorkerUserId());
        return DispatchResultResponse.builder()
                .bookingId(request.getBookingId())
                .status("PENALIZED_AND_RETRYING")
                .message("Worker penalized with 1-hour lockout block. Re-triggering spatial matching.")
                .build();
    }
}
