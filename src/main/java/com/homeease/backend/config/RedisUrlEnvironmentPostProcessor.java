package com.homeease.backend.config;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Maps the {@code REDIS_URL} environment variable (e.g. {@code rediss://default:<password>@host:6379})
 * onto Spring Boot's standard {@code spring.data.redis.url} property so the existing Lettuce
 * auto-configuration is reused unchanged (the {@code rediss://} scheme enables TLS).
 *
 * <p>Only applies when REDIS_URL is non-blank: Spring Boot rejects an empty
 * {@code spring.data.redis.url}, and leaving it unset preserves the existing
 * REDIS_HOST / REDIS_PORT / REDIS_PASSWORD behaviour. The value is never logged.
 */
public class RedisUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String ENV_VAR = "REDIS_URL";
    static final String TARGET_PROPERTY = "spring.data.redis.url";
    static final String SOURCE_NAME = "redisUrlFromEnvironment";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String url = environment.getProperty(ENV_VAR);
        if (url != null && !url.isBlank()) {
            environment.getPropertySources()
                    .addFirst(new MapPropertySource(SOURCE_NAME, Map.of(TARGET_PROPERTY, url.trim())));
        }
    }
}
