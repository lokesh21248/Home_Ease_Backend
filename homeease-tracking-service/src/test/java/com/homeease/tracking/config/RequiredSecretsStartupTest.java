package com.homeease.tracking.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/** Real Spring Boot startups against the committed tracking-service YAML (no web server, no auto-configuration). */
class RequiredSecretsStartupTest {

    @Configuration
    static class Minimal { }

    private ConfigurableApplicationContext start(String... args) {
        return new SpringApplicationBuilder(Minimal.class).web(WebApplicationType.NONE).logStartupInfo(false).run(args);
    }

    private static String chain(Throwable t) {
        StringBuilder all = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            all.append(c.getMessage()).append('\n');
        }
        return all.toString();
    }

    @Test
    void default_and_prod_profiles_refuse_to_start_without_a_jwt_secret() {
        // default profile: application.yml has ${JWT_SECRET} with no default
        assertThatThrownBy(() -> start())
                .satisfies(e -> assertThat(chain(e)).contains("JWT_SECRET"));
        // prod profile: application-prod.yml used to default it to empty, which must also be rejected
        assertThatThrownBy(() -> start("--spring.profiles.active=prod"))
                .satisfies(e -> assertThat(chain(e)).contains("JWT_SECRET"));
    }

    @Test
    void a_valid_secret_starts_and_a_short_one_does_not() {
        try (ConfigurableApplicationContext ctx = start("--spring.profiles.active=prod",
                "--JWT_SECRET=0123456789abcdef0123456789abcdef0123456789abcdef")) {
            assertThat(ctx.isActive()).isTrue();
        }
        assertThatThrownBy(() -> start("--spring.profiles.active=prod", "--JWT_SECRET=short"))
                .satisfies(e -> assertThat(chain(e)).contains("too short"));
    }

    @Test
    void the_dev_profile_starts_without_environment_secrets_but_never_with_prod() {
        try (ConfigurableApplicationContext ctx = start("--spring.profiles.active=dev")) {
            assertThat(ctx.getEnvironment().getProperty("homeease.jwt.secret")).hasSizeGreaterThanOrEqualTo(32);
        }
        assertThatThrownBy(() -> start("--spring.profiles.active=dev,prod"))
                .satisfies(e -> assertThat(chain(e)).contains("must not be combined"));
    }

    @Test
    void REDIS_URL_reaches_spring_data_redis_url_in_a_real_startup() {
        try (ConfigurableApplicationContext ctx = start("--spring.profiles.active=dev",
                "--REDIS_URL=rediss://default:placeholder@h.example:6379")) {
            assertThat(ctx.getEnvironment().getProperty("spring.data.redis.url"))
                    .isEqualTo("rediss://default:placeholder@h.example:6379");
        }
    }
}
