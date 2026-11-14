package com.homeease.tracking.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
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

    @Test
    void valid_jwt_secret_starts() {
        assertThatCode(() -> run(new RequiredSecretsEnvironmentPostProcessor(),
                new MockEnvironment().withProperty("homeease.jwt.secret", LONG_SECRET))).doesNotThrowAnyException();
    }

    @Test
    void missing_or_unresolvable_secret_fails_startup_naming_the_variable_only() {
        assertThatThrownBy(() -> run(new RequiredSecretsEnvironmentPostProcessor(), new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> run(new RequiredSecretsEnvironmentPostProcessor(),
                new MockEnvironment().withProperty("homeease.jwt.secret", "${JWT_SECRET}")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_SECRET is not set");
        // the production profile file's empty default ("${JWT_SECRET:}") must also be rejected
        assertThatThrownBy(() -> run(new RequiredSecretsEnvironmentPostProcessor(),
                new MockEnvironment().withProperty("homeease.jwt.secret", "")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void a_committed_secret_is_rejected_without_echoing_it_and_short_ones_too() {
        String leaked = "previously-committed-secret-value-0123456789";
        RequiredSecretsEnvironmentPostProcessor p = new RequiredSecretsEnvironmentPostProcessor(
                Set.of(RequiredSecretsEnvironmentPostProcessor.sha256(leaked)));
        assertThatThrownBy(() -> run(p, new MockEnvironment().withProperty("homeease.jwt.secret", leaked)))
                .hasMessageContaining("committed to Git history").hasMessageNotContaining(leaked);
        assertThatThrownBy(() -> run(new RequiredSecretsEnvironmentPostProcessor(),
                new MockEnvironment().withProperty("homeease.jwt.secret", "short")))
                .hasMessageContaining("too short");
    }

    @Test
    void dev_profile_skips_but_never_with_prod() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertThatCode(() -> run(new RequiredSecretsEnvironmentPostProcessor(), dev)).doesNotThrowAnyException();
        MockEnvironment both = new MockEnvironment();
        both.setActiveProfiles("dev", "production");
        assertThatThrownBy(() -> run(new RequiredSecretsEnvironmentPostProcessor(), both))
                .hasMessageContaining("must not be combined");
    }

    @Test
    void committed_yaml_has_no_jwt_default_and_dev_profile_is_valid_and_registered() throws IOException {
        PropertySource<?> main = new YamlPropertySourceLoader().load("m", new ClassPathResource("application.yml")).get(0);
        assertThat(String.valueOf(main.getProperty("homeease.jwt.secret"))).isEqualTo("${JWT_SECRET}");
        PropertySource<?> dev = new YamlPropertySourceLoader().load("d", new ClassPathResource("application-dev.yml")).get(0);
        assertThat(String.valueOf(dev.getProperty("homeease.jwt.secret")).length()).isGreaterThanOrEqualTo(32);
        assertThat(org.springframework.core.io.support.SpringFactoriesLoader.loadFactoryNames(
                org.springframework.boot.env.EnvironmentPostProcessor.class, getClass().getClassLoader()))
                .contains(RequiredSecretsEnvironmentPostProcessor.class.getName());
    }
}
