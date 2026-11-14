package com.homeease.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

/**
 * The REAL tracking application on a random port with Redis deliberately unreachable (port 1) and the dev profile
 * (so the secrets check is satisfied by the throw-away dev secret). Proves the hand-built Lettuce factory, the
 * post-processors and the health endpoints work together in a real context, and what callers receive.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=dev",
        "REDIS_HOST=127.0.0.1", "REDIS_PORT=1",
        "spring.data.redis.timeout=500ms", "spring.data.redis.connect-timeout=500ms"
})
class ApplicationSmokeTest {

    @Autowired
    private TestRestTemplate http;

    @Test
    void liveness_endpoint_does_not_touch_redis() {
        ResponseEntity<String> r = http.getForEntity("/api/v1/health", String.class);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void deep_health_is_503_with_only_an_exception_class_when_redis_is_unreachable() {
        ResponseEntity<String> r = http.getForEntity("/api/v1/health/detail", String.class);
        System.out.println("[tracking-smoke] /api/v1/health/detail -> " + r.getStatusCode().value() + " " + r.getBody());
        assertThat(r.getStatusCode().value()).isEqualTo(503);
        assertThat(r.getBody()).contains("\"status\":\"DOWN\"").contains("Exception");
        assertThat(r.getBody()).doesNotContain("Unable to connect").doesNotContain("127.0.0.1:1");
    }

    @Test
    void actuator_health_shows_an_anonymous_caller_no_component_details() {
        ResponseEntity<String> r = http.getForEntity("/actuator/health", String.class);
        System.out.println("[tracking-smoke] /actuator/health -> " + r.getStatusCode().value() + " " + r.getBody());
        assertThat(r.getBody()).doesNotContain("components").doesNotContain("127.0.0.1");
    }
}
