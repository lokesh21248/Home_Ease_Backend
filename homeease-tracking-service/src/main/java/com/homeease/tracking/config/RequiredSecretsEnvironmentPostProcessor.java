package com.homeease.tracking.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Fails startup, with a clear message, when a required production secret is missing or is a value that was
 * previously committed to Git.
 *
 * <p>Secrets must come from the environment (systemd {@code EnvironmentFile=/etc/homeease/backend.env}); the
 * committed YAML deliberately has no defaults for them. The message names variables only, never values.
 *
 * <p>Relaxed (no check) only under the {@code dev} or {@code test} profile, which use throw-away local values.
 * Combining {@code dev}/{@code test} with {@code prod}/{@code production} is itself rejected, so a development
 * profile can never silently run in production.
 */
public class RequiredSecretsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /**
     * SHA-256 of secret values that were committed to the repository's history and must never be accepted again
     * (database password, JWT signing secret). Hashes only: the values themselves are not stored here.
     */
    static final Set<String> COMPROMISED_SHA256 = Set.of(
            "da3264b2e5f77fe87c5ae5eb6ee1e8e66e9cf589aab86df74a2341a526d8f463",
            "3354cfcf65c7b2e6840ea6f880aede239750cb1d063950c4e8fd9fbf9ec73a32");

    private record Requirement(String property, String envNames, int minLength) { }

    private static final List<Requirement> REQUIRED = List.of(
            new Requirement("homeease.jwt.secret", "JWT_SECRET", 32));

    private final Set<String> compromisedHashes;

    public RequiredSecretsEnvironmentPostProcessor() {
        this(COMPROMISED_SHA256);
    }

    RequiredSecretsEnvironmentPostProcessor(Set<String> compromisedHashes) {
        this.compromisedHashes = compromisedHashes;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE; // after config files and profile activation are loaded
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        List<String> profiles = Arrays.asList(environment.getActiveProfiles());
        boolean relaxed = profiles.contains("dev") || profiles.contains("test");
        boolean production = profiles.contains("prod") || profiles.contains("production");
        if (relaxed && production) {
            throw new IllegalStateException("Refusing to start: the 'dev'/'test' profile must not be combined with "
                    + "'prod'/'production'. Development profiles contain throw-away credentials.");
        }
        if (relaxed) {
            return;
        }

        List<String> problems = new ArrayList<>();
        for (Requirement r : REQUIRED) {
            String value = resolve(environment, r.property());
            if (value == null || value.isBlank()) {
                problems.add(r.envNames() + " is not set");
            } else if (compromisedHashes.contains(sha256(value))) {
                problems.add(r.envNames() + " is set to a value that was committed to Git history; rotate it and "
                        + "set a new value");
            } else if (value.length() < r.minLength()) {
                problems.add(r.envNames() + " is too short (minimum " + r.minLength() + " characters)");
            }
        }
        if (!problems.isEmpty()) {
            StringBuilder msg = new StringBuilder("Refusing to start: required secrets are missing or unsafe.\n");
            for (String p : problems) {
                msg.append("  - ").append(p).append('\n');
            }
            msg.append("Provide them through the protected environment file (e.g. /etc/homeease/backend.env, "
                    + "mode 600) loaded by systemd EnvironmentFile=. For local development run with "
                    + "--spring.profiles.active=dev.");
            throw new IllegalStateException(msg.toString());
        }
    }

    /** Resolved value, or null when absent or when a ${PLACEHOLDER} inside it cannot be resolved. */
    private static String resolve(ConfigurableEnvironment environment, String property) {
        try {
            return environment.getProperty(property);
        } catch (IllegalArgumentException unresolvedPlaceholder) {
            return null;
        }
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
