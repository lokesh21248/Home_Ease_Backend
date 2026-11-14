package com.homeease.tracking.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.fppt.jedismock.RedisServer;
import com.homeease.tracking.config.RedisConfig;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Tracking /api/v1/health/detail against the REAL pooled factory built by RedisConfig (same pool settings as
 * production: max 64, 500 ms max wait). Local test doubles only; placeholder passwords.
 */
class HealthControllerRedisTest {

    private static final String PLACEHOLDER_PASSWORD = "placeholder-secret-do-not-leak";

    private LettuceConnectionFactory pooledFactory(String url, Duration commandTimeout) {
        RedisConfig c = new RedisConfig();
        ReflectionTestUtils.setField(c, "redisUrl", url);
        ReflectionTestUtils.setField(c, "redisHost", "unused");
        ReflectionTestUtils.setField(c, "redisPort", 6379);
        ReflectionTestUtils.setField(c, "redisPassword", "");
        ReflectionTestUtils.setField(c, "commandTimeout", commandTimeout);
        ReflectionTestUtils.setField(c, "connectTimeout", Duration.ofSeconds(2));
        LettuceConnectionFactory f = (LettuceConnectionFactory) c.redisConnectionFactory();
        f.afterPropertiesSet();
        return f;
    }

    private HealthController controller(LettuceConnectionFactory f) {
        RedisConfig c = new RedisConfig();
        StringRedisTemplate t = c.redisTemplate(f);
        return new HealthController(t);
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void repeated_probes_do_not_leak_pooled_connections() throws Exception {
        RedisServer server = RedisServer.newRedisServer().start();
        LettuceConnectionFactory f = pooledFactory("redis://127.0.0.1:" + server.getBindPort(), Duration.ofSeconds(2));
        try {
            HealthController hc = controller(f);
            assertThat(hc.detail().getStatusCode().value()).isEqualTo(200);
            // Baseline after the first probe: the pool holds ONE long-lived shared native connection that
            // Spring Data Redis keeps for plain commands (PING/INFO). It must not grow with probe count.
            int baseline = activePooledConnections(f);
            for (int i = 0; i < 300; i++) {
                ResponseEntity<Map<String, Object>> r = hc.detail();
                assertThat(r.getStatusCode().value()).as("probe #" + i).isEqualTo(200);
                assertThat(((Map<String, Object>) r.getBody().get("redis")).get("status")).isEqualTo("UP");
            }
            assertThat(activePooledConnections(f)).as("borrowed pooled connections after 300 more probes")
                    .isEqualTo(baseline);
        } finally {
            f.destroy();
            server.stop();
        }
    }

    /** Sum of GenericObjectPool.getNumActive() across the factory's pools, read reflectively. */
    @SuppressWarnings("unchecked")
    private static int activePooledConnections(LettuceConnectionFactory f) {
        Object provider = ReflectionTestUtils.getField(f, "connectionProvider");
        for (int depth = 0; depth < 4; depth++) { // unwrap ExceptionTranslatingConnectionProvider -> pooling provider
            try {
                provider = ReflectionTestUtils.getField(provider, "delegate");
            } catch (IllegalArgumentException noMoreWrappers) {
                break;
            }
        }
        Object pools = ReflectionTestUtils.getField(provider, "pools");
        int active = 0;
        for (Object pool : ((Map<Object, Object>) pools).values()) {
            active += ((org.apache.commons.pool2.impl.GenericObjectPool<?>) pool).getNumActive();
        }
        return active;
    }

    @Test
    @SuppressWarnings("unchecked")
    void unavailable_redis_returns_503_with_the_exception_class_only() throws Exception {
        LettuceConnectionFactory f = pooledFactory(
                "rediss://default:" + PLACEHOLDER_PASSWORD + "@127.0.0.1:" + freePort(), Duration.ofSeconds(1));
        try {
            ResponseEntity<Map<String, Object>> r = controller(f).detail();
            assertThat(r.getStatusCode().value()).isEqualTo(503);
            Map<String, Object> redis = (Map<String, Object>) r.getBody().get("redis");
            assertThat(redis.get("status")).isEqualTo("DOWN");
            assertThat(redis.get("error").toString()).matches("[A-Za-z]+Exception|[A-Za-z]+Error");
            assertThat(r.getBody().toString()).doesNotContain(PLACEHOLDER_PASSWORD).doesNotContain("rediss://");
        } finally {
            f.destroy();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void authentication_failure_and_timeout_do_not_leak_server_text_or_password() throws Exception {
        for (FakeRespServer.Mode mode : FakeRespServer.Mode.values()) {
            try (FakeRespServer server = new FakeRespServer(mode)) {
                LettuceConnectionFactory f = pooledFactory(
                        "redis://default:" + PLACEHOLDER_PASSWORD + "@127.0.0.1:" + server.port(), Duration.ofMillis(500));
                try {
                    long start = System.nanoTime();
                    ResponseEntity<Map<String, Object>> r = controller(f).detail();
                    long ms = (System.nanoTime() - start) / 1_000_000;
                    System.out.println("[tracking-health] " + mode + " -> " + r.getStatusCode().value() + " in " + ms + " ms: "
                            + ((Map<String, Object>) r.getBody().get("redis")).get("error"));
                    assertThat(r.getStatusCode().value()).isEqualTo(503);
                    assertThat(r.getBody().toString()).doesNotContain(PLACEHOLDER_PASSWORD).doesNotContain("WRONGPASS");
                    assertThat(ms).isLessThan(10_000);
                } finally {
                    f.destroy();
                }
            }
        }
    }
}
