package com.homeease.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

class RequiredSecretsEnvironmentPostProcessorTest {

    private static final String LONG_SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef";

    private void run(RequiredSecretsEnvironmentPostProcessor p, MockEnvironment env) {
        p.postProcessEnvironment(env, new SpringApplication());
    }

    private MockEnvironment valid() {
        return new MockEnvironment()
                .withProperty("spring.datasource.username", "app_user")
                .withProperty("spring.datasource.password", "a-strong-unique-password")
                .withProperty("homeease.jwt.secret", LONG_SECRET);
    }

    @Test
    void all_secrets_present_and_safe_starts() {
        assertThatCode(() -> run(new RequiredSecretsEnvironmentPostProcessor(), valid())).doesNotThrowAnyException();
    }

    @Test
    void missing_secrets_fail_startup_naming_the_variables_only() {
        assertThatThrownBy(() -> run(new RequiredSecretsEnvironmentPostProcessor(), new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_USER")
                .hasMessageContaining("DB_PASS")
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("--spring.profiles.active=dev");
    }

    @Test
    void unresolvable_placeholders_count_as_missing() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.username", "${DB_USER}")
                .withProperty("spring.datasource.password", "${DB_PASS}")
                .withProperty("homeease.jwt.secret", "${JWT_SECRET}");
        assertThatThrownBy(() -> run(new RequiredSecretsEnvironmentPostProcessor(), env))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_SECRET is not set");
    }

    @Test
    void a_value_that_was_committed_to_git_is_rejected_without_echoing_it() {
        String leaked = "previously-committed-secret-value-0123456789";
        RequiredSecretsEnvironmentPostProcessor p = new RequiredSecretsEnvironmentPostProcessor(
                Set.of(RequiredSecretsEnvironmentPostProcessor.sha256(leaked)));
        MockEnvironment env = valid().withProperty("homeease.jwt.secret", leaked);
        assertThatThrownBy(() -> run(p, env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("committed to Git history")
                .hasMessageNotContaining(leaked);
    }

    @Test
    void a_short_jwt_secret_is_rejected() {
        MockEnvironment env = valid().withProperty("homeease.jwt.secret", "too-short");
        assertThatThrownBy(() -> run(new RequiredSecretsEnvironmentPostProcessor(), env))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("too short");
    }

    @Test
    void dev_and_test_profiles_skip_the_check_but_never_alongside_prod() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertThatCode(() -> run(new RequiredSecretsEnvironmentPostProcessor(), dev)).doesNotThrowAnyException();

        MockEnvironment test = new MockEnvironment();
        test.setActiveProfiles("test");
        assertThatCode(() -> run(new RequiredSecretsEnvironmentPostProcessor(), test)).doesNotThrowAnyException();

        MockEnvironment both = new MockEnvironment();
        both.setActiveProfiles("dev", "prod");
        assertThatThrownBy(() -> run(new RequiredSecretsEnvironmentPostProcessor(), both))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("must not be combined");
    }

    @Test
    void the_committed_compromised_hashes_are_well_formed_sha256_values() {
        assertThat(RequiredSecretsEnvironmentPostProcessor.COMPROMISED_SHA256)
                .hasSize(2).allMatch(h -> h.matches("[0-9a-f]{64}"));
    }

    @Test
    void the_post_processor_is_registered() {
        assertThat(org.springframework.core.io.support.SpringFactoriesLoader.loadFactoryNames(
                org.springframework.boot.env.EnvironmentPostProcessor.class, getClass().getClassLoader()))
                .contains(RequiredSecretsEnvironmentPostProcessor.class.getName(),
                        RedisUrlEnvironmentPostProcessor.class.getName());
    }

    @Test
    void committed_yaml_has_no_secret_defaults_left() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("main", new ClassPathResource("application.yml"));
        PropertySource<?> main = sources.get(0);
        for (String key : List.of("spring.datasource.username", "spring.datasource.password", "homeease.jwt.secret")) {
            String raw = String.valueOf(main.getProperty(key));
            assertThat(raw).as(key).matches("\\$\\{[A-Z_]+}"); // ${VAR} with NO ":default"
        }
    }

    @Test
    void the_dev_profile_is_local_only_and_self_consistent() throws IOException {
        PropertySource<?> dev = new YamlPropertySourceLoader()
                .load("dev", new ClassPathResource("application-dev.yml")).get(0);
        assertThat(String.valueOf(dev.getProperty("spring.datasource.url"))).contains("localhost");
        assertThat(String.valueOf(dev.getProperty("homeease.jwt.secret")).length()).isGreaterThanOrEqualTo(32);
        // and the dev secret is not one of the leaked ones
        assertThat(RequiredSecretsEnvironmentPostProcessor.COMPROMISED_SHA256)
                .doesNotContain(RequiredSecretsEnvironmentPostProcessor.sha256(
                        String.valueOf(dev.getProperty("homeease.jwt.secret"))));
    }
}
