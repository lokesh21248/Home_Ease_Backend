package com.homeease.tracking.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Live check of the tracking service's hand-built Lettuce factory (TLS + pooling) against REDIS_URL.
 * SKIPPED unless REDIS_URL is set. All keys/channels carry a unique {@code test:compat:<uuid>:} prefix
 * (production key names and the real ws:broadcast:tracking channel are NOT touched, so no fake events can
 * reach real customers). Only those keys are deleted; no FLUSH.
 */
class TrackingRedisLiveTest {

    private static final String REDIS_URL = System.getenv("REDIS_URL");
    private final String p = "test:compat:" + UUID.randomUUID() + ":";
    private LettuceConnectionFactory factory;
    private StringRedisTemplate t;

    @BeforeEach
    void connect() {
        assumeTrue(REDIS_URL != null && !REDIS_URL.isBlank(), "REDIS_URL not set - live test skipped");
        RedisConfig c = new RedisConfig();
        ReflectionTestUtils.setField(c, "redisUrl", REDIS_URL);
        ReflectionTestUtils.setField(c, "redisHost", "unused");
        ReflectionTestUtils.setField(c, "redisPort", 6379);
        ReflectionTestUtils.setField(c, "redisPassword", "");
        ReflectionTestUtils.setField(c, "commandTimeout", Duration.ofMillis(3000));
        ReflectionTestUtils.setField(c, "connectTimeout", Duration.ofMillis(5000));
        factory = (LettuceConnectionFactory) c.redisConnectionFactory();
        factory.afterPropertiesSet();
        t = c.redisTemplate(factory);
    }

    @AfterEach
    void close() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @Test
    void authenticated_ping_over_tls() {
        assertThat(factory.isUseSsl()).isTrue();
        assertThat(t.execute((org.springframework.data.redis.core.RedisCallback<String>) cn -> cn.ping()))
                .isEqualToIgnoringCase("PONG");
    }

    @Test
    void tracking_key_json_roundtrip_with_ttl_and_geo() {
        String trackKey = p + "tracking:booking:B1";
        String geoKey = p + "active_workers_geo";
        try {
            String json = "{\"bookingId\":\"B1\",\"lat\":12.9716,\"lng\":77.5946,\"sequenceNumber\":7}";
            t.opsForValue().set(trackKey, json, Duration.ofHours(2));           // same call as persistToRedisAndGeo
            t.opsForGeo().add(geoKey, new Point(77.5946, 12.9716), "w-1");
            assertThat(t.opsForValue().get(trackKey)).isEqualTo(json);
            assertThat(t.getExpire(trackKey, TimeUnit.SECONDS)).isBetween(7000L, 7200L);
            var res = t.opsForGeo().radius(geoKey, new Circle(new Point(77.5946, 12.9716),
                    new Distance(1, Metrics.KILOMETERS)));
            assertThat(res.getContent()).extracting(r -> r.getContent().getName()).containsExactly("w-1");
        } finally {
            t.delete(trackKey);
            t.delete(geoKey);
        }
    }

    @Test
    void pubsub_publish_from_tracking_is_received_by_a_bridge_style_subscriber() throws Exception {
        String channel = p + "ws:broadcast:tracking";
        CountDownLatch got = new CountDownLatch(1);
        AtomicReference<String> body = new AtomicReference<>();
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        // identical decoding to the core's RedisWebSocketBridge: raw bytes -> UTF-8 JSON
        container.addMessageListener((message, pattern) -> {
            body.set(new String(message.getBody(), StandardCharsets.UTF_8));
            got.countDown();
        }, new ChannelTopic(channel));
        container.afterPropertiesSet();
        container.start();
        try {
            String msg = "{\"destination\":\"/topic/tracking/B1\",\"bookingId\":\"B1\",\"payload\":{\"lat\":1.5}}";
            for (int i = 0; i < 20 && got.getCount() > 0; i++) {   // subscription is asynchronous
                t.convertAndSend(channel, msg);                       // same call LiveTrackingService makes
                got.await(500, TimeUnit.MILLISECONDS);
            }
            assertThat(got.getCount()).isZero();
            assertThat(body.get()).isEqualTo(msg);
        } finally {
            container.stop();
            container.destroy();
        }
    }
}
