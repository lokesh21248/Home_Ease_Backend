package com.homeease.tracking.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Health check endpoints for HomeEase Phase 1 deployment.
 *
 * <ul>
 *   <li>GET /api/v1/health        – Lightweight liveness probe (no Redis check).
 *       Used by AWS Route 53 DNS failover health checks via Nginx /healthz passthrough.</li>
 *   <li>GET /api/v1/health/detail – Deep readiness probe: Redis ping latency + memory stats.
 *       Used by internal monitoring (Prometheus scrape / Uptime Kuma).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    private final StringRedisTemplate redisTemplate;

    @Value("${spring.application.name:homeease-tracking-service}")
    private String applicationName;

    @Value("${server.port:8082}")
    private int serverPort;

    public HealthController(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Lightweight liveness check – returns 200 immediately.
     * Does NOT touch Redis or any downstream service so Route 53 / Nginx
     * health probes always get a fast, reliable response.
     */
    @GetMapping({"", "/"})
    public ResponseEntity<Map<String, Object>> liveness() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", applicationName);
        body.put("port", serverPort);
        body.put("timestamp", nowIso());
        return ResponseEntity.ok(body);
    }

    /**
     * Deep readiness check: verifies Redis connectivity, measures ping latency,
     * and reports memory usage for capacity monitoring.
     *
     * Returns 503 if Redis is unreachable so load balancers can drain this node.
     */
    @GetMapping("/detail")
    public ResponseEntity<Map<String, Object>> detail() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("service", applicationName);
        response.put("port", serverPort);
        response.put("timestamp", nowIso());

        Map<String, Object> redisHealth = new LinkedHashMap<>();
        long startMs = System.currentTimeMillis();
        try {
            // PING round-trip latency
            redisTemplate.getConnectionFactory().getConnection().ping();
            long latencyMs = System.currentTimeMillis() - startMs;
            redisHealth.put("status", "UP");
            redisHealth.put("pingLatencyMs", latencyMs);

            // Memory info
            try {
                Properties memInfo = redisTemplate.getConnectionFactory()
                        .getConnection()
                        .serverCommands()
                        .info("memory");
                if (memInfo != null) {
                    redisHealth.put("usedMemoryHuman",  memInfo.getProperty("used_memory_human", "?"));
                    redisHealth.put("maxMemoryHuman",   memInfo.getProperty("maxmemory_human", "?"));
                    redisHealth.put("evictionPolicy",   memInfo.getProperty("maxmemory_policy", "?"));
                    redisHealth.put("fragRatio",        memInfo.getProperty("mem_fragmentation_ratio", "?"));
                }
            } catch (Exception ex) {
                redisHealth.put("memoryInfo", "unavailable");
            }

            // Stats: eviction and command counts
            try {
                Properties stats = redisTemplate.getConnectionFactory()
                        .getConnection()
                        .serverCommands()
                        .info("stats");
                if (stats != null) {
                    redisHealth.put("evictedKeys",             stats.getProperty("evicted_keys", "0"));
                    redisHealth.put("totalCommandsProcessed",  stats.getProperty("total_commands_processed", "?"));
                }
            } catch (Exception ex) {
                redisHealth.put("statsInfo", "unavailable");
            }

            response.put("status", "UP");
            response.put("redis", redisHealth);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            redisHealth.put("status", "DOWN");
            redisHealth.put("error", e.getMessage());
            redisHealth.put("pingLatencyMs", System.currentTimeMillis() - startMs);
            response.put("status", "DEGRADED");
            response.put("redis", redisHealth);
            // 503 causes Nginx to mark this upstream server as temporarily unavailable
            return ResponseEntity.status(503).body(response);
        }
    }

    private static String nowIso() {
        return Instant.now().atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
