package com.homeease.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

/**
 * The REAL dispatch application, started on a random port with Redis deliberately unreachable (port 1). Proves the
 * actual bean wiring (post-processor, ping indicator, Redis auto-configuration, presence property) and what an
 * anonymous caller receives from the health endpoints. No Redis, no credentials, nothing external is contacted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "REDIS_HOST=127.0.0.1", "REDIS_PORT=1",
        "spring.data.redis.timeout=500ms", "spring.data.redis.connect-timeout=500ms"
})
class ApplicationSmokeTest {

    @Autowired
    private TestRestTemplate http;

    @Test
    void liveness_endpoint_does_not_depend_on_redis() {
        ResponseEntity<String> r = http.getForEntity("/api/v1/health", String.class);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void actuator_health_reports_DOWN_for_unreachable_redis_and_shows_an_anonymous_caller_no_details() {
        ResponseEntity<String> r = http.getForEntity("/actuator/health", String.class);
        System.out.println("[dispatch-smoke] /actuator/health -> " + r.getStatusCode().value() + " " + r.getBody());
        assertThat(r.getStatusCode().value()).isEqualTo(503);
        assertThat(r.getBody()).contains("\"status\":\"DOWN\"");
        // show-details: when-authorized => anonymous callers get the status only
        assertThat(r.getBody()).doesNotContain("components").doesNotContain("redisPing")
                .doesNotContain("127.0.0.1").doesNotContain("Exception");
    }
}
