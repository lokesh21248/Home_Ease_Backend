package com.homeease.tracking.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.fppt.jedismock.RedisServer;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The tracking service's hand-built Lettuce factory (autoReconnect + REJECT_COMMANDS + pooling, built by
 * {@link RedisConfig}) must fail fast during an outage and recover on its own afterwards. Local mock server; this is
 * not evidence about Upstash.
 */
class TrackingRedisRecoveryTest {

    @Test
    void commands_fail_fast_during_an_outage_and_succeed_again_after_the_server_returns() throws Exception {
        RedisServer server = RedisServer.newRedisServer().start();
        int port = server.getBindPort();

        RedisConfig c = new RedisConfig();
        ReflectionTestUtils.setField(c, "redisUrl", "redis://127.0.0.1:" + port);
        ReflectionTestUtils.setField(c, "redisHost", "unused");
        ReflectionTestUtils.setField(c, "redisPort", 6379);
        ReflectionTestUtils.setField(c, "redisPassword", "");
        ReflectionTestUtils.setField(c, "commandTimeout", Duration.ofMillis(1000));
        ReflectionTestUtils.setField(c, "connectTimeout", Duration.ofSeconds(1));
        LettuceConnectionFactory f = (LettuceConnectionFactory) c.redisConnectionFactory();
        f.afterPropertiesSet();
        StringRedisTemplate t = c.redisTemplate(f);
        RedisCallback<String> ping = conn -> conn.ping();
        RedisServer restarted = null;
        try {
            assertThat(t.execute(ping)).isEqualToIgnoringCase("PONG");

            server.stop(); // outage
            long start = System.nanoTime();
            boolean failed = false;
            try {
                t.execute(ping);
            } catch (RuntimeException expected) {
                failed = true;
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            System.out.println("[tracking-recovery] command during outage failed=" + failed + " after " + ms + " ms");
            assertThat(failed).as("a command during the outage must fail, not hang or silently succeed").isTrue();
            assertThat(ms).as("fails fast (command timeout is 1 s, REJECT_COMMANDS while disconnected)").isLessThan(6_000);

            restarted = RedisServer.newRedisServer(port).start(); // outage ends
            long deadline = System.currentTimeMillis() + 20_000;
            String reply = null;
            while (System.currentTimeMillis() < deadline) {
                try {
                    reply = t.execute(ping);
                    if ("PONG".equalsIgnoreCase(reply)) {
                        break;
                    }
                } catch (RuntimeException stillReconnecting) {
                    Thread.sleep(300);
                }
            }
            System.out.println("[tracking-recovery] after the server returned: " + reply);
            assertThat(reply).as("the factory reconnected without a restart").isEqualToIgnoringCase("PONG");
        } finally {
            f.destroy();
            if (restarted != null) {
                restarted.stop();
            }
            try { server.stop(); } catch (Exception alreadyStopped) { }
        }
    }
}
