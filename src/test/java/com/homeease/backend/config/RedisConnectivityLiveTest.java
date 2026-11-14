package com.homeease.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Live connectivity check against the Redis named by the REDIS_URL environment variable, using the
 * same Spring Boot / Lettuce auto-configuration as the application (TLS via the rediss:// scheme).
 *
 * <p>SKIPPED (not passed) when REDIS_URL is absent. Touches only keys under a unique
 * {@code test:connectivity:<uuid>:} prefix and deletes exactly those keys afterwards. It never
 * flushes Redis and never prints the URL.
 */
class RedisConnectivityLiveTest {

    private static final String REDIS_URL = System.getenv("REDIS_URL");
    private final String prefix = "test:connectivity:" + UUID.randomUUID() + ":";

    @BeforeEach
    void requireRedisUrl() {
        assumeTrue(REDIS_URL != null && !REDIS_URL.isBlank(), "REDIS_URL not set - live Redis test skipped");
    }

    private void withTemplate(Consumer<StringRedisTemplate> body) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withPropertyValues(
                        "spring.data.redis.url=" + REDIS_URL,
                        "spring.data.redis.timeout=3000ms",
                        "spring.data.redis.connect-timeout=5000ms")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    body.accept(ctx.getBean(StringRedisTemplate.class));
                });
    }

    private void withFactory(java.util.function.BiConsumer<StringRedisTemplate,
            org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory> body) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withPropertyValues(
                        "spring.data.redis.url=" + REDIS_URL,
                        "spring.data.redis.timeout=3000ms",
                        "spring.data.redis.connect-timeout=5000ms")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    body.accept(ctx.getBean(StringRedisTemplate.class),
                            ctx.getBean(org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory.class));
                });
    }

    @Test
    void tls_is_in_use_and_the_server_certificate_is_accepted_by_the_jvm() {
        withFactory((t, f) -> {
            assertThat(f.isUseSsl()).as("REDIS_URL must be rediss:// (TLS)").isTrue();
            // Lettuce verifies the peer certificate and host name by default: a successful PING proves the
            // JVM-side TLS handshake and certificate validation against the system trust store.
            String reply = t.execute((org.springframework.data.redis.core.RedisCallback<String>) c -> c.ping());
            System.out.println("[redis-live] TLS in use=" + f.isUseSsl() + ", PING over TLS -> " + reply);
            assertThat(reply).isEqualToIgnoringCase("PONG");
        });
    }

    @Test
    void a_dropped_connection_is_re_established_with_tls_and_authentication() {
        withFactory((t, f) -> {
            org.springframework.data.redis.core.RedisCallback<String> ping = c -> c.ping();
            assertThat(t.execute(ping)).isEqualToIgnoringCase("PONG");
            String key = prefix + "recovery";
            try {
                t.opsForValue().set(key, "before", Duration.ofSeconds(60));
                f.resetConnection(); // closes the live connection; the next command must reconnect (TLS + AUTH)
                System.out.println("[redis-live] connection reset; issuing next command");
                assertThat(t.execute(ping)).as("PING after the connection was dropped").isEqualToIgnoringCase("PONG");
                assertThat(t.opsForValue().get(key)).as("data written before the drop is still readable").isEqualTo("before");
                System.out.println("[redis-live] recovery after connection drop: OK");
            } finally {
                t.delete(key);
            }
        });
    }

    @Test
    void ping() {
        withTemplate(t -> {
            String reply = t.execute((org.springframework.data.redis.core.RedisCallback<String>) c -> c.ping());
            System.out.println("[redis-live] PING -> " + reply);
            assertThat(reply).isEqualToIgnoringCase("PONG");
        });
    }

    @Test
    void setAndGet() {
        withTemplate(t -> {
            String key = prefix + "kv";
            try {
                t.opsForValue().set(key, "bar", Duration.ofSeconds(60));
                String value = t.opsForValue().get(key);
                System.out.println("[redis-live] SET/GET round-trip value=" + value);
                assertThat(value).isEqualTo("bar");
            } finally {
                t.delete(key);
            }
        });
    }

    @Test
    void geoAddAndSearch() {
        withTemplate(t -> {
            String key = prefix + "geo";
            try {
                t.opsForGeo().add(key, new Point(77.5946, 12.9716), "near-worker");   // Bengaluru centre
                t.opsForGeo().add(key, new Point(77.6100, 12.9800), "mid-worker");    // ~2 km away
                t.opsForGeo().add(key, new Point(72.8777, 19.0760), "far-worker");    // Mumbai
                GeoResults<RedisGeoCommands.GeoLocation<String>> res = t.opsForGeo().radius(
                        key,
                        new Circle(new Point(77.5946, 12.9716), new Distance(5, Metrics.KILOMETERS)),
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeDistance().sortAscending());
                List<String> names = res.getContent().stream().map(r -> r.getContent().getName()).toList();
                System.out.println("[redis-live] GEO radius 5km -> " + names);
                assertThat(names).containsExactly("near-worker", "mid-worker");
            } finally {
                t.delete(key);
            }
        });
    }

    @Test
    void heartbeatTtl() throws Exception {
        withTemplate(t -> {
            String key = prefix + "heartbeat";
            try {
                t.opsForValue().set(key, "ALIVE", Duration.ofSeconds(3));
                Long ttl = t.getExpire(key, java.util.concurrent.TimeUnit.SECONDS);
                System.out.println("[redis-live] heartbeat TTL right after set = " + ttl + "s");
                assertThat(ttl).isBetween(1L, 3L);
                assertThat(t.hasKey(key)).isTrue();
                Thread.sleep(4000);
                System.out.println("[redis-live] heartbeat exists after 4s = " + t.hasKey(key));
                assertThat(t.hasKey(key)).isFalse();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                t.delete(key);
            }
        });
    }
}
