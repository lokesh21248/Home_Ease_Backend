package com.homeease.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Regression guard: no source, configuration, script or deployment file may contain a value that was previously
 * committed to Git history and is therefore compromised (database password, JWT secret). The values themselves are
 * not stored anywhere in the code: every candidate token is hashed and compared with
 * {@link RequiredSecretsEnvironmentPostProcessor#COMPROMISED_SHA256}. Only file names and line numbers are reported.
 *
 * <p>This exists because the first clean-up removed the literal defaults from application.yml but missed the same
 * secret hard-coded as an {@code @Value} fallback in two Java classes.
 */
class NoCompromisedSecretsInSourceTest {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9@#!_.\\-]{20,80}");
    private static final Set<String> SCAN_ROOTS = Set.of(
            "src/main", "homeease-dispatch-service/src", "homeease-tracking-service/src",
            "homeease-notification-service/src", "deploy", "infra", "nginx", "scripts", ".github");
    private static final Set<String> SCAN_ROOT_FILES = Set.of("docker-compose.yml", "Dockerfile", "index.html",
            "adminApi.js", "test_suite.js", "test_redis.js", "live-tracking-test.html", "pom.xml");
    private static final Set<String> TEXT_EXTENSIONS = Set.of("java", "yml", "yaml", "properties", "xml", "sh", "ps1",
            "js", "html", "conf", "service", "example", "json", "sql", "factories");

    @Test
    void no_file_contains_a_value_that_was_committed_to_git_history() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        List<String> findings = new ArrayList<>();
        for (String r : SCAN_ROOTS) {
            Path dir = root.resolve(r);
            if (Files.isDirectory(dir)) {
                try (Stream<Path> files = Files.walk(dir)) {
                    for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                        scan(root, f, findings);
                    }
                }
            }
        }
        for (String name : SCAN_ROOT_FILES) {
            Path f = root.resolve(name);
            if (Files.isRegularFile(f)) {
                scan(root, f, findings);
            }
        }
        assertThat(findings).as("files that still contain a compromised secret value (file:line only)").isEmpty();
    }

    @Test
    void the_scanner_itself_detects_a_planted_value() throws IOException {
        // Self-test with a throw-away value so a broken scanner cannot give a false "clean" result.
        String planted = "planted-test-secret-0123456789abcdef";
        Path tmp = Files.createTempFile("planted", ".java");
        try {
            Files.writeString(tmp, "class X { String s = \"" + planted + "\"; }\n");
            List<String> findings = new ArrayList<>();
            scanFile(tmp, Set.of(RequiredSecretsEnvironmentPostProcessor.sha256(planted)), tmp.getParent(), findings);
            assertThat(findings).hasSize(1);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void scan(Path root, Path file, List<String> findings) throws IOException {
        String name = file.getFileName().toString();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!TEXT_EXTENSIONS.contains(ext) && !name.endsWith(".env.example")) {
            return;
        }
        if (file.toString().replace('\\', '/').contains("/target/") || file.toString().contains("node_modules")) {
            return;
        }
        scanFile(file, RequiredSecretsEnvironmentPostProcessor.COMPROMISED_SHA256, root, findings);
    }

    private static void scanFile(Path file, Set<String> compromised, Path root, List<String> findings) throws IOException {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (java.nio.charset.MalformedInputException notText) {
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = TOKEN.matcher(lines.get(i));
            while (m.find()) {
                if (compromised.contains(RequiredSecretsEnvironmentPostProcessor.sha256(m.group()))) {
                    findings.add(root.relativize(file).toString().replace('\\', '/') + ":" + (i + 1));
                }
            }
        }
    }
}
