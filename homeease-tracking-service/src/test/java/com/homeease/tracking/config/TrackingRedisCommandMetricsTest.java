package com.homeease.tracking.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.fppt.jedismock.RedisServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** The tracking service's hand-built factory must report per-command counts like Boot-built factories do. */
class TrackingRedisCommandMetricsTest {

    private RedisConfig config(int port, SimpleMeterRegistry registry) {
        RedisConfig c = new RedisConfig();
        ReflectionTestUtils.setField(c, "redisUrl", "redis://127.0.0.1:" + port);
        ReflectionTestUtils.setField(c, "redisHost", "unused");
        ReflectionTestUtils.setField(c, "redisPort", 6379);
        ReflectionTestUtils.setField(c, "redisPassword", "");
        ReflectionTestUtils.setField(c, "commandTimeout", Duration.ofSeconds(2));
        ReflectionTestUtils.setField(c, "connectTimeout", Duration.ofSeconds(2));
        ReflectionTestUtils.setField(c, "meterRegistry", registry);
        return c;
    }

    private static long count(SimpleMeterRegistry registry, String command) {
        var timer = registry.find("lettuce.command.completion").tag("command", command).timer();
        return timer == null ? 0 : timer.count();
    }

    @Test
    void commands_are_counted_per_name_and_resources_are_released_on_shutdown() throws Exception {
        RedisServer server = RedisServer.newRedisServer().start();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedisConfig c = config(server.getBindPort(), registry);
        LettuceConnectionFactory f = (LettuceConnectionFactory) c.redisConnectionFactory();
        f.afterPropertiesSet();
        try {
            StringRedisTemplate t = c.redisTemplate(f);
            t.opsForValue().set("m:1", "x", Duration.ofSeconds(30));
            t.opsForValue().get("m:1");
            t.opsForValue().get("m:1");
            t.delete("m:1");
            System.out.println("[tracking-metrics] SETEX=" + count(registry, "SETEX") + " GET=" + count(registry, "GET")
                    + " DEL=" + count(registry, "DEL"));
            assertThat(count(registry, "SETEX")).isEqualTo(1);
            assertThat(count(registry, "GET")).isEqualTo(2);
            assertThat(count(registry, "DEL")).isEqualTo(1);
        } finally {
            f.destroy();
            c.shutdownMetricsResources(); // what @PreDestroy does in the running application
            server.stop();
        }
        assertThat(ReflectionTestUtils.getField(c, "metricsClientResources")).as("released").isNull();
    }

    @Test
    void without_a_meter_registry_the_factory_is_built_exactly_as_before() {
        RedisConfig c = config(6379, null);
        LettuceConnectionFactory f = (LettuceConnectionFactory) c.redisConnectionFactory();
        assertThat(ReflectionTestUtils.getField(c, "metricsClientResources")).isNull();
        f.destroy();
    }
}
