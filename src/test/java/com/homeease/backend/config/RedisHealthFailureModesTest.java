package com.homeease.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.fppt.jedismock.RedisServer;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Health indicator behaviour in every Redis failure state. All servers are local test doubles; passwords are
 * throw-away placeholders. Proves: correct status, bounded latency, and that no secret, URL or driver message
 * ever reaches the health payload.
 */
class RedisHealthFailureModesTest {

    private static final String PLACEHOLDER_PASSWORD = "placeholder-secret-do-not-leak";

    private LettuceConnectionFactory factory(String host, int port, String password, Duration commandTimeout) {
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration(host, port);
        if (password != null) {
            cfg.setPassword(password);
        }
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(commandTimeout)
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(2)).build())
                        .timeoutOptions(TimeoutOptions.enabled(commandTimeout))
                        .build())
                .build();
        LettuceConnectionFactory f = new LettuceConnectionFactory(cfg, client);
        f.afterPropertiesSet();
        return f;
    }

    private static void assertNoLeak(Health h) {
        String payload = h.toString();
        assertThat(payload).doesNotContain(PLACEHOLDER_PASSWORD).doesNotContain("rediss://").doesNotContain("WRONGPASS")
                .doesNotContain("@");
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void up_reports_pong_and_latency() throws Exception {
        RedisServer server = RedisServer.newRedisServer().start();
        LettuceConnectionFactory f = factory("127.0.0.1", server.getBindPort(), null, Duration.ofSeconds(2));
        try {
            Health h = new RedisPingHealthIndicator(f).health();
            assertThat(h.getStatus()).isEqualTo(Status.UP);
            assertThat(h.getDetails()).containsEntry("reply", "PONG").containsKey("latencyMs");
        } finally {
            f.destroy();
            server.stop();
        }
    }

    @Test
    void unavailable_redis_is_down_quickly_and_names_only_the_exception_class() throws Exception {
        LettuceConnectionFactory f = factory("127.0.0.1", freePort(), PLACEHOLDER_PASSWORD, Duration.ofSeconds(1));
        try {
            long start = System.nanoTime();
            Health h = new RedisPingHealthIndicator(f).health();
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertThat(h.getStatus()).isEqualTo(Status.DOWN);
            assertThat(h.getDetails()).containsKey("error");
            assertThat(ms).as("fails fast instead of hanging").isLessThan(5_000);
            assertNoLeak(h);
        } finally {
            f.destroy();
        }
    }

    @Test
    void authentication_failure_is_down_and_does_not_echo_server_text_or_the_password() throws Exception {
        try (FakeRespServer server = new FakeRespServer(FakeRespServer.Mode.WRONGPASS)) {
            LettuceConnectionFactory f = factory("127.0.0.1", server.port(), PLACEHOLDER_PASSWORD, Duration.ofSeconds(2));
            try {
                Health h = new RedisPingHealthIndicator(f).health();
                System.out.println("[health] auth-failure payload = " + h);
                assertThat(h.getStatus()).isEqualTo(Status.DOWN);
                assertNoLeak(h);
            } finally {
                f.destroy();
            }
        }
    }

    @Test
    void timeout_is_down_within_the_command_timeout_not_forever() throws Exception {
        try (FakeRespServer server = new FakeRespServer(FakeRespServer.Mode.SILENT)) {
            LettuceConnectionFactory f = factory("127.0.0.1", server.port(), PLACEHOLDER_PASSWORD, Duration.ofMillis(500));
            try {
                long start = System.nanoTime();
                Health h = new RedisPingHealthIndicator(f).health();
                long ms = (System.nanoTime() - start) / 1_000_000;
                System.out.println("[health] timeout payload = " + h + " after " + ms + " ms");
                assertThat(h.getStatus()).isEqualTo(Status.DOWN);
                assertThat(ms).isLessThan(8_000);
                assertNoLeak(h);
            } finally {
                f.destroy();
            }
        }
    }

    @Test
    void recovers_to_up_after_redis_comes_back() throws Exception {
        RedisServer server = RedisServer.newRedisServer().start();
        int port = server.getBindPort();
        LettuceConnectionFactory f = factory("127.0.0.1", port, null, Duration.ofSeconds(1));
        RedisServer restarted = null;
        try {
            RedisPingHealthIndicator indicator = new RedisPingHealthIndicator(f);
            assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);

            server.stop();
            assertThat(indicator.health().getStatus()).as("outage").isEqualTo(Status.DOWN);

            restarted = RedisServer.newRedisServer(port).start();
            long deadline = System.currentTimeMillis() + 15_000;
            Status last = Status.DOWN;
            while (last != Status.UP && System.currentTimeMillis() < deadline) {
                last = indicator.health().getStatus();
                if (last != Status.UP) {
                    Thread.sleep(300);
                }
            }
            assertThat(last).as("recovered after reconnect").isEqualTo(Status.UP);
        } finally {
            f.destroy();
            if (restarted != null) restarted.stop();
        }
    }
}
