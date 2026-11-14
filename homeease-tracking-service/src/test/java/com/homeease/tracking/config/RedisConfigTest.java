package com.homeease.tracking.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

/** Offline checks with placeholder passwords only; proves URL/TLS wiring, not connectivity. */
class RedisConfigTest {

    private RedisConfig config(String url, String host, String password) {
        RedisConfig c = new RedisConfig();
        ReflectionTestUtils.setField(c, "redisUrl", url);
        ReflectionTestUtils.setField(c, "redisHost", host);
        ReflectionTestUtils.setField(c, "redisPort", 6379);
        ReflectionTestUtils.setField(c, "redisPassword", password);
        ReflectionTestUtils.setField(c, "commandTimeout", Duration.ofMillis(2000));
        ReflectionTestUtils.setField(c, "connectTimeout", Duration.ofMillis(5000));
        return c;
    }

    @Test
    void rediss_url_enables_tls_user_password_and_connect_timeout() {
        LettuceConnectionFactory f = (LettuceConnectionFactory) config(
                "rediss://default:placeholder-pw@example-db.upstash.io:6379", "ignored-host", "ignored-pw")
                .redisConnectionFactory();
        assertThat(f.isUseSsl()).isTrue();
        assertThat(f.getHostName()).isEqualTo("example-db.upstash.io");
        assertThat(f.getPort()).isEqualTo(6379);
        assertThat(f.getStandaloneConfiguration().getUsername()).isEqualTo("default");
        assertThat(new String(f.getStandaloneConfiguration().getPassword().get())).isEqualTo("placeholder-pw");
        assertThat(f.getClientConfiguration().getClientOptions().get().getSocketOptions().getConnectTimeout())
                .isEqualTo(Duration.ofMillis(5000));
    }

    @Test
    void legacy_host_port_password_still_works_without_tls() {
        LettuceConnectionFactory f = (LettuceConnectionFactory) config("", "127.0.0.1", "legacy-pw")
                .redisConnectionFactory();
        assertThat(f.isUseSsl()).isFalse();
        assertThat(f.getHostName()).isEqualTo("127.0.0.1");
        assertThat(new String(f.getStandaloneConfiguration().getPassword().get())).isEqualTo("legacy-pw");
        assertThat(f.getStandaloneConfiguration().getUsername()).isNull();
    }

    @Test
    void parse_handles_password_only_userinfo_percent_decoding_and_database() {
        RedisConfig.RedisTarget t = RedisConfig.parseUrl("redis://:p%40ss@h.example:6380/2");
        assertThat(t.ssl()).isFalse();
        assertThat(t.port()).isEqualTo(6380);
        assertThat(t.database()).isEqualTo(2);
        assertThat(t.password()).isEqualTo("p@ss"); // percent-decoded via URI.getUserInfo(), as Spring Boot does
    }

    @Test
    void invalid_urls_are_rejected_without_echoing_the_value() {
        assertThatThrownBy(() -> RedisConfig.parseUrl("http://default:topsecret@h:6379"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("topsecret");
        assertThatThrownBy(() -> RedisConfig.parseUrl("rediss://default:top secret@h:6379"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("secret");
    }

    @Test
    void post_processor_maps_REDIS_URL_and_ignores_blank_and_is_registered() {
        MockEnvironment set = new MockEnvironment().withProperty("REDIS_URL", "rediss://default:x@h:6379");
        new RedisUrlEnvironmentPostProcessor().postProcessEnvironment(set, new SpringApplication());
        assertThat(set.getProperty("spring.data.redis.url")).isEqualTo("rediss://default:x@h:6379");

        MockEnvironment blank = new MockEnvironment();
        new RedisUrlEnvironmentPostProcessor().postProcessEnvironment(blank, new SpringApplication());
        assertThat(blank.containsProperty("spring.data.redis.url")).isFalse();

        assertThat(org.springframework.core.io.support.SpringFactoriesLoader.loadFactoryNames(
                org.springframework.boot.env.EnvironmentPostProcessor.class, getClass().getClassLoader()))
                .contains(RedisUrlEnvironmentPostProcessor.class.getName());
    }
}
