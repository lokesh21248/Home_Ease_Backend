package com.homeease.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Offline check that a rediss:// URL binds to TLS + the right host/port through Spring Boot's
 * standard Redis auto-configuration. Uses a throw-away placeholder password and opens NO network
 * connection, so it proves configuration binding only - not connectivity.
 */
class RedisUrlBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class));

    @Test
    void rediss_url_enables_tls_and_overrides_host_port() {
        runner.withPropertyValues(
                        "spring.data.redis.url=rediss://default:placeholder-not-a-real-secret@example-db.upstash.io:6379",
                        "spring.data.redis.host=localhost",
                        "spring.data.redis.connect-timeout=5000ms")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    LettuceConnectionFactory f = ctx.getBean(LettuceConnectionFactory.class);
                    assertThat(f.isUseSsl()).isTrue();
                    assertThat(f.getHostName()).isEqualTo("example-db.upstash.io");
                    assertThat(f.getPort()).isEqualTo(6379);
                    assertThat(f.getClientConfiguration().getClientOptions()).isPresent();
                    assertThat(f.getClientConfiguration().getClientOptions().get().getSocketOptions()
                            .getConnectTimeout().toMillis()).isEqualTo(5000);
                });
    }

    @Test
    void without_url_keeps_plain_host_port_behaviour() {
        runner.withPropertyValues(
                        "spring.data.redis.host=localhost",
                        "spring.data.redis.port=6379")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    LettuceConnectionFactory f = ctx.getBean(LettuceConnectionFactory.class);
                    assertThat(f.isUseSsl()).isFalse();
                    assertThat(f.getHostName()).isEqualTo("localhost");
                });
    }

    @Test
    void post_processor_maps_REDIS_URL_to_spring_property() {
        MockEnvironment env = new MockEnvironment().withProperty("REDIS_URL", "rediss://default:placeholder@h:6379");
        new RedisUrlEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getProperty("spring.data.redis.url")).isEqualTo("rediss://default:placeholder@h:6379");
    }

    @Test
    void post_processor_is_registered_in_spring_factories() {
        assertThat(org.springframework.core.io.support.SpringFactoriesLoader
                .loadFactoryNames(org.springframework.boot.env.EnvironmentPostProcessor.class,
                        getClass().getClassLoader()))
                .contains(RedisUrlEnvironmentPostProcessor.class.getName());
    }

    @Test
    void post_processor_ignores_unset_or_blank_REDIS_URL() {
        for (String value : new String[] {null, "", "   "}) {
            MockEnvironment env = new MockEnvironment();
            if (value != null) {
                env.setProperty("REDIS_URL", value);
            }
            new RedisUrlEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());
            assertThat(env.containsProperty("spring.data.redis.url")).isFalse();
        }
    }
}
