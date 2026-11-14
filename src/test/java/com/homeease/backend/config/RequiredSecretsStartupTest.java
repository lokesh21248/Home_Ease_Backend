package com.homeease.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Real Spring Boot startups (no web server, no database, no auto-configuration) proving the secrets check runs in
 * the actual application lifecycle against the real committed application.yml: command-line arguments override the
 * surefire "test" profile so the production code path is exercised.
 */
class RequiredSecretsStartupTest {

    @Configuration
    static class Minimal { }

    private ConfigurableApplicationContext start(String... args) {
        return new SpringApplicationBuilder(Minimal.class)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false)
                .run(args);
    }

    private static String rootMessage(Throwable t) {
        StringBuilder all = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            all.append(c.getMessage()).append('\n');
        }
        return all.toString();
    }

    @Test
    void production_profile_without_secrets_refuses_to_start_and_names_the_variables() {
        assertThatThrownBy(() -> start("--spring.profiles.active=prod"))
                .satisfies(e -> assertThat(rootMessage(e))
                        .contains("required secrets are missing or unsafe")
                        .contains("DB_USER").contains("DB_PASS").contains("JWT_SECRET"));
    }

    @Test
    void production_profile_with_valid_secrets_starts() {
        try (ConfigurableApplicationContext ctx = start("--spring.profiles.active=prod",
                "--DB_USER=app_user", "--DB_PASS=a-strong-unique-password",
                "--JWT_SECRET=0123456789abcdef0123456789abcdef0123456789abcdef")) {
            assertThat(ctx.isActive()).isTrue();
        }
    }

    @Test
    void the_standard_spring_env_var_names_for_the_database_also_satisfy_the_check() {
        // Real OS environment variables are exposed through a SystemEnvironmentPropertySource, which is what gives
        // SPRING_DATASOURCE_PASSWORD its relaxed mapping to spring.datasource.password (command-line args do not).
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource("fakeOsEnv", java.util.Map.of(
                "SPRING_DATASOURCE_USERNAME", "app_user",
                "SPRING_DATASOURCE_PASSWORD", "a-strong-unique-password",
                "JWT_SECRET", "0123456789abcdef0123456789abcdef0123456789abcdef")));
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(Minimal.class)
                .web(WebApplicationType.NONE).logStartupInfo(false).environment(env)
                .run("--spring.profiles.active=prod")) {
            assertThat(ctx.isActive()).isTrue();
        }
    }

    @Test
    void the_dev_profile_starts_without_any_secrets_from_the_environment() {
        try (ConfigurableApplicationContext ctx = start("--spring.profiles.active=dev")) {
            assertThat(ctx.isActive()).isTrue();
            assertThat(ctx.getEnvironment().getProperty("spring.datasource.url")).contains("localhost");
        }
    }

    @Test
    void dev_combined_with_prod_is_refused() {
        assertThatThrownBy(() -> start("--spring.profiles.active=dev,prod"))
                .satisfies(e -> assertThat(rootMessage(e)).contains("must not be combined"));
    }

    @Test
    void REDIS_URL_from_the_environment_reaches_spring_data_redis_url_in_a_real_startup() {
        try (ConfigurableApplicationContext ctx = start("--spring.profiles.active=test",
                "--REDIS_URL=rediss://default:placeholder@h.example:6379")) {
            assertThat(ctx.getEnvironment().getProperty("spring.data.redis.url"))
                    .isEqualTo("rediss://default:placeholder@h.example:6379");
        }
    }
}
