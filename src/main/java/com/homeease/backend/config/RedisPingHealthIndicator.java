package com.homeease.backend.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * Lightweight Redis liveness probe exposed at /actuator/health as "redisPing".
 *
 * <p>Issues a plain PING (supported by every managed Redis, including Upstash) and reports the
 * round-trip latency. Never includes the connection URL, host credentials or password in the
 * health payload.
 */
@Component("redisPing")
public class RedisPingHealthIndicator implements HealthIndicator {

    private final RedisConnectionFactory connectionFactory;

    public RedisPingHealthIndicator(RedisConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public Health health() {
        long start = System.nanoTime();
        try (RedisConnection connection = connectionFactory.getConnection()) {
            String reply = connection.ping();
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            if ("PONG".equalsIgnoreCase(reply)) {
                return Health.up().withDetail("reply", "PONG").withDetail("latencyMs", latencyMs).build();
            }
            return Health.down().withDetail("reply", String.valueOf(reply)).build();
        } catch (Exception e) {
            // Only the exception type: messages can echo connection details.
            return Health.down().withDetail("error", e.getClass().getSimpleName()).build();
        }
    }
}
