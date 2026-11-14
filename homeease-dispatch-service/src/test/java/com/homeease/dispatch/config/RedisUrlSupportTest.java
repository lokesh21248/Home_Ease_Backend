package com.homeease.dispatch.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.mock.env.MockEnvironment;

/** Offline checks (no network, placeholder password only): proves config binding, not connectivity. */
class RedisUrlSupportTest {

    @Test
    void rediss_url_enables_tls_and_overrides_host_port() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withPropertyValues(
                        "spring.data.redis.url=rediss://default:placeholder-not-a-real-secret@example-db.upstash.io:6379",
                        "spring.data.redis.host=localhost")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    LettuceConnectionFactory f = ctx.getBean(LettuceConnectionFactory.class);
                    assertThat(f.isUseSsl()).isTrue();
                    assertThat(f.getHostName()).isEqualTo("example-db.upstash.io");
                    assertThat(f.getPort()).isEqualTo(6379);
                });
    }

    @Test
    void post_processor_maps_REDIS_URL_and_ignores_blank() {
        MockEnvironment set = new MockEnvironment().withProperty("REDIS_URL", "rediss://default:x@h:6379");
        new RedisUrlEnvironmentPostProcessor().postProcessEnvironment(set, new SpringApplication());
        assertThat(set.getProperty("spring.data.redis.url")).isEqualTo("rediss://default:x@h:6379");

        MockEnvironment blank = new MockEnvironment().withProperty("REDIS_URL", "  ");
        new RedisUrlEnvironmentPostProcessor().postProcessEnvironment(blank, new SpringApplication());
        assertThat(blank.containsProperty("spring.data.redis.url")).isFalse();
    }

    @Test
    void post_processor_is_registered() {
        assertThat(org.springframework.core.io.support.SpringFactoriesLoader.loadFactoryNames(
                org.springframework.boot.env.EnvironmentPostProcessor.class, getClass().getClassLoader()))
                .contains(RedisUrlEnvironmentPostProcessor.class.getName());
    }

    @Test
    void ping_indicator_reports_up_and_down_without_leaking_messages() {
        RedisConnectionFactory ok = mock(RedisConnectionFactory.class);
        RedisConnection okConn = mock(RedisConnection.class);
        when(ok.getConnection()).thenReturn(okConn);
        when(okConn.ping()).thenReturn("PONG");
        assertThat(new RedisPingHealthIndicator(ok).health().getStatus()).isEqualTo(Status.UP);

        RedisConnectionFactory bad = mock(RedisConnectionFactory.class);
        when(bad.getConnection()).thenThrow(new IllegalStateException("secret-host-and-password"));
        var health = new RedisPingHealthIndicator(bad).health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().toString()).doesNotContain("secret-host-and-password");
    }
}
