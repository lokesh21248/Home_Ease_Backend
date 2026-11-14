package com.homeease.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Structural checks on the GitHub workflows, because they cannot be executed here. They prove the files parse and
 * that the safety properties are wired: tests gate the deployment, and the pre-flight runs before the build and
 * before the services are restarted. They do NOT prove the workflows run successfully on GitHub.
 */
class WorkflowFilesTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String file) throws IOException {
        return (Map<String, Object>) new Yaml().load(Files.readString(Path.of(".github/workflows/" + file)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void deployment_is_gated_by_the_ci_workflow() throws IOException {
        Map<String, Object> jobs = (Map<String, Object>) load("deploy.yml").get("jobs");
        Map<String, Object> ci = (Map<String, Object>) jobs.get("ci");
        Map<String, Object> deploy = (Map<String, Object>) jobs.get("deploy");
        assertThat(ci.get("uses")).isEqualTo("./.github/workflows/ci.yml");
        assertThat(deploy.get("needs")).as("deploy must wait for the tests").isEqualTo("ci");
    }

    @Test
    void the_preflight_runs_before_the_build_the_install_and_the_restart() throws IOException {
        String script = Files.readString(Path.of(".github/workflows/deploy.yml"));
        int preflight = script.indexOf("deploy/preflight-env.sh");
        int build = script.indexOf("mvn clean package");
        int install = script.indexOf("sudo cp target/");
        int restart = script.indexOf("systemctl restart");
        assertThat(preflight).isPositive();
        assertThat(preflight).as("pre-flight before build").isLessThan(build);
        assertThat(preflight).as("pre-flight before install").isLessThan(install);
        assertThat(preflight).as("pre-flight before restart").isLessThan(restart);
        assertThat(script).contains("set -e"); // a failing pre-flight aborts the remote script
        assertThat(script).contains("homeease-core:core").contains("homeease-tracking:tracking");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ci_runs_on_pull_requests_and_is_callable_and_never_deploys() throws IOException {
        Map<String, Object> ci = load("ci.yml");
        // YAML 1.1 parses the key "on" as boolean true
        Map<String, Object> triggers = (Map<String, Object>) (ci.containsKey("on") ? ci.get("on") : ci.get(Boolean.TRUE));
        assertThat(triggers).containsKeys("pull_request", "workflow_call");
        assertThat(Files.readString(Path.of(".github/workflows/ci.yml"))).doesNotContain("systemctl").doesNotContain("ssh-action");
        Map<String, Object> jobs = (Map<String, Object>) ci.get("jobs");
        assertThat(jobs).containsKeys("unit", "integration-postgis");
        assertThat(((Map<String, Object>) jobs.get("integration-postgis")).get("continue-on-error")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void the_ci_integration_job_uses_a_password_less_throwaway_postgis_and_the_guarded_local_url() throws IOException {
        Map<String, Object> job = (Map<String, Object>) ((Map<String, Object>) load("ci.yml").get("jobs")).get("integration-postgis");
        Map<String, Object> postgis = (Map<String, Object>) ((Map<String, Object>) job.get("services")).get("postgis");
        assertThat(String.valueOf(postgis.get("image"))).startsWith("postgis/postgis");
        Map<String, Object> env = (Map<String, Object>) postgis.get("env");
        assertThat(env.get("POSTGRES_HOST_AUTH_METHOD")).isEqualTo("trust");
        assertThat(env).as("no password anywhere").doesNotContainKey("POSTGRES_PASSWORD");
        String url = String.valueOf(((Map<String, Object>) ((List<Map<String, Object>>) job.get("steps")).get(2).get("env")).get("TEST_POSTGIS_JDBC_URL"));
        // the same rules the test itself enforces
        com.homeease.backend.integration.DisposableDatabaseGuard.requireSafe(url);
    }
}
