package com.homeease.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.fppt.jedismock.RedisServer;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Capability probe for the in-process Redis server used by presence tests. */
class MockRedisSmokeTest {

    @Test
    void server_supports_the_commands_the_presence_logic_needs() throws Exception {
        RedisServer server = RedisServer.newRedisServer().start();
        LettuceConnectionFactory f = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(server.getHost(), server.getBindPort()));
        f.afterPropertiesSet();
        try {
            StringRedisTemplate t = new StringRedisTemplate(f);
            t.opsForValue().set("a", "1", Duration.ofSeconds(60));
            assertThat(t.getExpire("a")).isBetween(1L, 60L);
            assertThat(t.opsForValue().multiGet(List.of("a", "nope"))).containsExactly("1", null);
            t.opsForZSet().add("g", "w1", 1.0); // GEO sets are plain sorted sets; the mock lacks GEOADD
            assertThat(t.opsForZSet().range("g", 0, -1)).containsExactly("w1");
            Long removed = t.execute(new DefaultRedisScript<>(
                    "local r=0 for i=2,#KEYS do if redis.call('EXISTS',KEYS[i])==0 then r=r+redis.call('ZREM',KEYS[1],ARGV[i-1]) end end return r",
                    Long.class), List.of("g", "heartbeat-missing"), "w1");
            assertThat(removed).isEqualTo(1L);
        } finally {
            f.destroy();
            server.stop();
        }
    }
}
