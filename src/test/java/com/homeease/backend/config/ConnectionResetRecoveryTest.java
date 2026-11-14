package com.homeease.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.fppt.jedismock.RedisServer;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Validates the exact recovery sequence used by the authenticated live test
 * ({@code RedisConnectivityLiveTest.a_dropped_connection_is_re_established...}) against a local mock server, so that
 * when it runs against Upstash a failure points at Upstash/TLS/auth rather than at the test's own logic.
 * This is NOT evidence about Upstash.
 */
class ConnectionResetRecoveryTest {

    @Test
    void a_reset_connection_reconnects_and_earlier_data_is_still_readable() throws Exception {
        RedisServer server = RedisServer.newRedisServer().start();
        LettuceConnectionFactory f = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(server.getHost(), server.getBindPort()));
        f.afterPropertiesSet();
        try {
            StringRedisTemplate t = new StringRedisTemplate(f);
            RedisCallback<String> ping = c -> c.ping();
            assertThat(t.execute(ping)).isEqualToIgnoringCase("PONG");
            t.opsForValue().set("recovery-key", "before", Duration.ofSeconds(60));

            f.resetConnection();

            assertThat(t.execute(ping)).isEqualToIgnoringCase("PONG");
            assertThat(t.opsForValue().get("recovery-key")).isEqualTo("before");
        } finally {
            f.destroy();
            server.stop();
        }
    }
}
