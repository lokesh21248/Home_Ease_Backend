package com.homeease.tracking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.tracking.dto.LocationPayload;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * LIVE + SHARED KEYS: the real tracking-service ingest ({@link LiveTrackingService#processLocationUpdate}) against
 * the Redis named by REDIS_URL. Runs ONLY with REDIS_LIVE_ALLOW_SHARED_KEYS=true because the production code path
 * necessarily touches production names:
 * <ul>
 *   <li>{@code tracking:assignment|booking|seq:<random uuid>} and {@code homeease:tracking:<random uuid>:location}
 *       (a booking id no real booking can have; all deleted afterwards);</li>
 *   <li>one member (a random worker id) in the shared GEO set {@code active_workers_geo} (removed afterwards);</li>
 *   <li>one PUBLISH on the real channel {@code ws:broadcast:tracking}. Deployed core services receive it and drop it
 *       harmlessly: the topic for an unknown booking has no subscribers and no booking row exists.</li>
 * </ul>
 * Never flushes; never prints the URL.
 */
class LiveTrackingUpstashSharedKeysLiveTest {

    private static final String REDIS_URL = System.getenv("REDIS_URL");

    @Test
    void real_ingest_stores_latest_location_publishes_to_websocket_bridge_and_stops_after_completion() {
        assumeTrue(REDIS_URL != null && !REDIS_URL.isBlank(), "REDIS_URL not set - live test skipped");
        assumeTrue("true".equalsIgnoreCase(System.getenv("REDIS_LIVE_ALLOW_SHARED_KEYS")),
                "REDIS_LIVE_ALLOW_SHARED_KEYS!=true - shared-key test skipped");

        String bookingId = UUID.randomUUID().toString();
        String workerId = UUID.randomUUID().toString();
        String customerId = UUID.randomUUID().toString();

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withPropertyValues("spring.data.redis.url=" + REDIS_URL,
                        "spring.data.redis.timeout=5000ms", "spring.data.redis.connect-timeout=8000ms")
                .run(ctx -> {
                    StringRedisTemplate t = ctx.getBean(StringRedisTemplate.class);
                    RedisConnectionFactory cf = ctx.getBean(RedisConnectionFactory.class);
                    ObjectMapper mapper = new ObjectMapper();
                    SimpMessagingTemplate stomp = mock(SimpMessagingTemplate.class);
                    LiveTrackingService svc = new LiveTrackingService(t, mapper, stomp);
                    ReflectionTestUtils.setField(svc, "windowSeconds", 60);
                    ReflectionTestUtils.setField(svc, "maxUpdates", 30);
                    ReflectionTestUtils.setField(svc, "movementThresholdMeters", 15.0);
                    ReflectionTestUtils.setField(svc, "maxAccuracyMeters", 50.0);
                    ReflectionTestUtils.setField(svc, "requireAssignment", true);
                    svc.init();

                    CountDownLatch published = new CountDownLatch(1);
                    AtomicReference<String> envelope = new AtomicReference<>();
                    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
                    container.setConnectionFactory(cf);
                    container.addMessageListener((message, pattern) -> {
                        String body = new String(message.getBody(), StandardCharsets.UTF_8);
                        if (body.contains(bookingId)) { // other bookings' real traffic is ignored, never logged
                            envelope.set(body);
                            published.countDown();
                        }
                    }, new ChannelTopic("ws:broadcast:tracking"));
                    container.afterPropertiesSet();
                    container.start();
                    try {
                        Thread.sleep(1_500); // let the SUBSCRIBE complete before publishing
                        // what the core writes on acceptance: participants + status
                        t.opsForValue().set("tracking:assignment:" + bookingId,
                                "{\"bookingId\":\"" + bookingId + "\",\"customerId\":\"" + customerId + "\",\"userId\":\""
                                        + workerId + "\",\"workerId\":\"" + workerId + "\",\"status\":\"ON_THE_WAY\","
                                        + "\"trackingActive\":true}", Duration.ofMinutes(5));

                        long now = System.currentTimeMillis();
                        LocationPayload in = LocationPayload.builder().bookingId(bookingId).lat(17.443512).lng(78.377215)
                                .bearing(90.0).speed(8.0).accuracy(6.0).timestamp(now - 500).build();
                        LocationPayload out = svc.processLocationUpdate(bookingId, in, workerId);
                        assertThat(out.getSequenceNumber()).isNotNull();

                        // 1. WebSocket broadcast on the booking topic (the customer's subscription)
                        verify(stomp).convertAndSend(eq("/topic/tracking/" + bookingId), any(LocationPayload.class));

                        // 2. Redis pub/sub to the core bridge (real channel)
                        assertThat(published.await(10, TimeUnit.SECONDS)).as("ws:broadcast:tracking delivered").isTrue();
                        JsonNode env = mapper.readTree(envelope.get());
                        assertThat(env.path("destination").asText()).isEqualTo("/topic/tracking/" + bookingId);
                        assertThat(env.path("payload").path("lat").asDouble()).isEqualTo(17.443512);

                        // 3. latest location persisted in Upstash (async writer) with its TTLs
                        String latest = null;
                        for (int i = 0; i < 40 && latest == null; i++) {
                            latest = t.opsForValue().get("tracking:booking:" + bookingId);
                            if (latest == null) Thread.sleep(250);
                        }
                        assertThat(latest).as("tracking:booking:{id} written").isNotNull();
                        assertThat(mapper.readTree(latest).path("lng").asDouble()).isEqualTo(78.377215);
                        Long ttlLatest = t.getExpire("tracking:booking:" + bookingId);
                        Long ttlShort = t.getExpire("homeease:tracking:" + bookingId + ":location");
                        System.out.println("[upstash-live] ingest stored latest fix: TTL tracking:booking=" + ttlLatest
                                + "s, homeease:tracking:*:location=" + ttlShort + "s");
                        assertThat(ttlLatest).isBetween(1L, 7200L);
                        assertThat(ttlShort).isBetween(1L, 120L);

                        // 4. booking completed: the core marks the assignment closed -> ingest refuses (worker app stops)
                        t.opsForValue().set("tracking:assignment:" + bookingId,
                                "{\"bookingId\":\"" + bookingId + "\",\"userId\":\"" + workerId + "\",\"status\":\"COMPLETED\","
                                        + "\"trackingActive\":false}", Duration.ofMinutes(5));
                        LocationPayload after = LocationPayload.builder().bookingId(bookingId).lat(17.4436).lng(78.3773)
                                .accuracy(6.0).timestamp(System.currentTimeMillis()).build();
                        assertThatThrownBy(() -> svc.processLocationUpdate(bookingId, after, workerId))
                                .isInstanceOf(IllegalStateException.class);
                    } finally {
                        container.stop();
                        container.destroy();
                        svc.shutdown();
                        t.delete(java.util.List.of("tracking:assignment:" + bookingId, "tracking:booking:" + bookingId,
                                "tracking:seq:" + bookingId, "homeease:tracking:" + bookingId + ":location"));
                        t.opsForZSet().remove("active_workers_geo", workerId);
                    }
                });
    }
}
