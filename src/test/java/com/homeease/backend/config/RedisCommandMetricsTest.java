package com.homeease.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.fppt.jedismock.RedisServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.redis.LettuceMetricsAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Proves the application counts every Redis command it sends, per command name, through Spring Boot's own Lettuce
 * Micrometer integration (no custom code). In staging this is what turns the capacity ESTIMATE into a MEASUREMENT:
 * {@code lettuce_command_completion_seconds_count} on /actuator/prometheus, summed over a window, is the
 * application-side command rate. It is not Upstash's own billing meter (which does not bill PING/INFO/AUTH/HELLO).
 */
class RedisCommandMetricsTest {

    @Test
    void every_command_is_counted_per_command_name_through_boots_lettuce_metrics() throws Exception {
        RedisServer server = RedisServer.newRedisServer().start();
        try {
            new ApplicationContextRunner()
                    .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                    .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class, LettuceMetricsAutoConfiguration.class))
                    .withPropertyValues("spring.data.redis.host=" + server.getHost(),
                            "spring.data.redis.port=" + server.getBindPort())
                    .run(ctx -> {
                        assertThat(ctx).hasNotFailed();
                        StringRedisTemplate t = ctx.getBean(StringRedisTemplate.class);
                        MeterRegistry registry = ctx.getBean(MeterRegistry.class);

                        t.opsForValue().set("metrics:a", "1", Duration.ofSeconds(30));
                        t.opsForValue().get("metrics:a");
                        t.opsForValue().get("metrics:a");
                        t.opsForValue().get("metrics:missing");
                        t.delete("metrics:a");

                        registry.find("lettuce.command.completion").timers().forEach(tm ->
                                System.out.println("[metrics] command=" + tm.getId().getTag("command") + " count=" + tm.count()));
                        long sets = count(registry, "SET") + count(registry, "SETEX") + count(registry, "PSETEX");
                        long gets = count(registry, "GET");
                        long dels = count(registry, "DEL");
                        System.out.println("[metrics] counted SET=" + sets + " GET=" + gets + " DEL=" + dels
                                + " (issued 1 / 3 / 1)");
                        assertThat(sets).isEqualTo(1);
                        assertThat(gets).isEqualTo(3);
                        assertThat(dels).isEqualTo(1);
                    });
        } finally {
            server.stop();
        }
    }

    private static long count(MeterRegistry registry, String command) {
        var timer = registry.find("lettuce.command.completion").tag("command", command).timer();
        return timer == null ? 0 : timer.count();
    }
}
