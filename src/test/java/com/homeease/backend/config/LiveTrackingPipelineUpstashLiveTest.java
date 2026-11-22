package com.homeease.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.tracking.ArrivalDetectionService;
import com.homeease.backend.tracking.DeviceStatusStore;
import com.homeease.backend.tracking.GpsFix;
import com.homeease.backend.tracking.LatestFixReader;
import com.homeease.backend.tracking.LocationHistoryRecorder;
import com.homeease.backend.tracking.TrackingFixConsumer;
import com.homeease.backend.tracking.TrackingPhase;
import com.homeease.backend.tracking.TrackingPhaseResolver;
import com.homeease.backend.tracking.TrackingPhaseResolver.DeviceGpsState;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * LIVE: the core side of the tracking pipeline against the real Redis named by REDIS_URL (Upstash, rediss://).
 *
 * <p>Real classes on a real connection: {@link LatestFixReader}, {@link DeviceStatusStore},
 * {@link RedisWebSocketBridge} (Redis pub/sub -> STOMP), {@link TrackingFixConsumer} (pub/sub -> history/geofence),
 * {@link TrackingPhaseResolver}. Only the STOMP broker and the database-backed collaborators are recorded mocks.</p>
 *
 * <p>Safety: SKIPPED without REDIS_URL. Keys use a fresh random booking id that no real booking can have
 * ({@code tracking:booking:<uuid>}, {@code tracking:device:<uuid>}) with short TTLs and are deleted afterwards.
 * Pub/sub uses an ISOLATED channel {@code test:pipeline:<uuid>:ws:broadcast:tracking}, so production services
 * subscribed to the real channel receive nothing. Nothing is flushed; the URL is never printed.</p>
 */
class LiveTrackingPipelineUpstashLiveTest {

    private static final String REDIS_URL = System.getenv("REDIS_URL");
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID bookingId = UUID.randomUUID();
    private final String channel = "test:pipeline:" + UUID.randomUUID() + ":ws:broadcast:tracking";

    @BeforeEach
    void requireRedis() {
        assumeTrue(REDIS_URL != null && !REDIS_URL.isBlank(), "REDIS_URL not set - live Upstash test skipped");
    }

    private void live(BiConsumer<StringRedisTemplate, LettuceConnectionFactory> body) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withPropertyValues(
                        "spring.data.redis.url=" + REDIS_URL,
                        "spring.data.redis.timeout=5000ms",
                        "spring.data.redis.connect-timeout=8000ms")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    StringRedisTemplate t = ctx.getBean(StringRedisTemplate.class);
                    try {
                        body.accept(t, ctx.getBean(LettuceConnectionFactory.class));
                    } finally {
                        t.delete(List.of(LatestFixReader.LOCATION_KEY_PREFIX + bookingId, "tracking:device:" + bookingId));
                    }
                });
    }

    /** The JSON the tracking service stores in tracking:booking:{id} and publishes in the bridge envelope. */
    private String fixJson(double lat, double lng, long deviceTs, long serverTs) {
        return String.format(Locale.US,
                "{\"partnerId\":\"%s\",\"workerId\":\"%s\",\"bookingId\":\"%s\",\"lat\":%.6f,\"lng\":%.6f,"
                        + "\"bearing\":92.5,\"speed\":8.4,\"accuracy\":6.0,\"timestamp\":%d,\"serverTimestamp\":%d,"
                        + "\"sequenceNumber\":1,\"trackingActive\":true,\"status\":\"TRACKING\"}",
                UUID.randomUUID(), UUID.randomUUID(), bookingId, lat, lng, deviceTs, serverTs);
    }

    @Test
    void authenticated_ping_over_tls_with_latency() {
        live((t, f) -> {
            assertThat(f.isUseSsl()).as("REDIS_URL must use rediss:// (TLS)").isTrue();
            List<Long> ms = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                long s = System.nanoTime();
                String pong = t.execute((RedisCallback<String>) c -> c.ping());
                ms.add((System.nanoTime() - s) / 1_000_000);
                assertThat(pong).isEqualToIgnoringCase("PONG");
            }
            Collections.sort(ms);
            System.out.println("[upstash-live] TLS=" + f.isUseSsl() + " port=" + f.getPort()
                    + " authenticated PING x5 ok, round-trip ms min/median/max = "
                    + ms.get(0) + "/" + ms.get(2) + "/" + ms.get(4));
        });
    }

    @Test
    void latest_fix_set_get_ttl_and_stop_through_the_real_reader() {
        live((t, f) -> {
            long now = System.currentTimeMillis();
            String key = LatestFixReader.LOCATION_KEY_PREFIX + bookingId;
            t.opsForValue().set(key, fixJson(17.443512, 78.377215, now - 1_000, now), java.time.Duration.ofSeconds(120));

            Long ttl = t.getExpire(key);
            System.out.println("[upstash-live] SET latest fix, TTL = " + ttl + "s");
            assertThat(ttl).isBetween(1L, 120L);

            LatestFixReader reader = new LatestFixReader(t, mapper);
            LatestFixReader.LatestFix fix = reader.read(bookingId).orElseThrow();
            assertThat(fix.lat()).isEqualTo(17.443512);
            assertThat(fix.lng()).isEqualTo(78.377215);
            assertThat(fix.receivedAtMs()).isEqualTo(now);
            assertThat(fix.headingDeg()).isEqualTo(92.5);

            // booking cancelled / completed: the core deletes the key; the reader must then report nothing
            assertThat(t.delete(key)).isTrue();
            assertThat(reader.read(bookingId)).isEmpty();
        });
    }

    @Test
    void stale_location_is_never_live() {
        live((t, f) -> {
            long now = System.currentTimeMillis();
            String key = LatestFixReader.LOCATION_KEY_PREFIX + bookingId;
            TrackingPhaseResolver resolver = new TrackingPhaseResolver();
            LatestFixReader reader = new LatestFixReader(t, mapper);

            t.opsForValue().set(key, fixJson(17.44, 78.38, now - 46_000, now - 45_000), java.time.Duration.ofSeconds(120));
            LatestFixReader.LatestFix old = reader.read(bookingId).orElseThrow();
            TrackingPhaseResolver.Resolution stale = resolver.resolve(new TrackingPhaseResolver.Input(
                    BookingStage.ON_THE_WAY, old.receivedAtMs(), 900.0, DeviceGpsState.OK, System.currentTimeMillis()));
            assertThat(stale.phase()).isEqualTo(TrackingPhase.TRACKING_INTERRUPTED);
            assertThat(stale.live()).isFalse();

            long fresh = System.currentTimeMillis();
            t.opsForValue().set(key, fixJson(17.44, 78.38, fresh - 500, fresh), java.time.Duration.ofSeconds(120));
            LatestFixReader.LatestFix now2 = reader.read(bookingId).orElseThrow();
            TrackingPhaseResolver.Resolution live = resolver.resolve(new TrackingPhaseResolver.Input(
                    BookingStage.ON_THE_WAY, now2.receivedAtMs(), 900.0, DeviceGpsState.OK, System.currentTimeMillis()));
            assertThat(live.phase()).isEqualTo(TrackingPhase.PARTNER_EN_ROUTE);
            assertThat(live.live()).isTrue();
        });
    }

    @Test
    void device_status_round_trip_with_ttl() {
        live((t, f) -> {
            DeviceStatusStore store = new DeviceStatusStore(t);
            store.report(bookingId, DeviceGpsState.PERMISSION_DENIED);
            assertThat(store.read(bookingId)).isEqualTo(DeviceGpsState.PERMISSION_DENIED);
            Long ttl = t.getExpire("tracking:device:" + bookingId);
            System.out.println("[upstash-live] device-status TTL = " + ttl + "s");
            assertThat(ttl).isBetween(1L, 180L);
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void pubsub_fix_reaches_stomp_topic_and_the_geofence_consumer() {
        live((t, f) -> {
            SimpMessagingTemplate stomp = mock(SimpMessagingTemplate.class);
            RedisWebSocketBridge bridge = new RedisWebSocketBridge(stomp, mapper);
            ArrivalDetectionService arrival = mock(ArrivalDetectionService.class);
            TrackingFixConsumer consumer = new TrackingFixConsumer(mapper,
                    (ObjectProvider<RedisMessageListenerContainer>) mock(ObjectProvider.class),
                    mock(LocationHistoryRecorder.class), arrival);

            List<Boolean> relayed = new CopyOnWriteArrayList<>();
            List<Boolean> consumed = new CopyOnWriteArrayList<>();
            List<Long> latencyMs = new CopyOnWriteArrayList<>();
            CountDownLatch got = new CountDownLatch(2);
            long[] sentAt = new long[1];

            RedisMessageListenerContainer container = new RedisMessageListenerContainer();
            container.setConnectionFactory((RedisConnectionFactory) f);
            container.addMessageListener((message, pattern) -> {
                latencyMs.add((System.nanoTime() - sentAt[0]) / 1_000_000);
                String body = new String(message.getBody(), StandardCharsets.UTF_8);
                relayed.add(bridge.relay(body));
                consumed.add(consumer.handle(body));
                got.countDown();
            }, new ChannelTopic(channel));
            container.afterPropertiesSet();
            container.start();
            try {
                long now = System.currentTimeMillis();
                String good = "{\"destination\":\"/topic/tracking/" + bookingId + "\",\"bookingId\":\"" + bookingId
                        + "\",\"payload\":" + fixJson(17.4486, 78.3908, now - 800, now) + "}";
                // a forged envelope naming another booking in its payload must never be delivered
                String forged = "{\"destination\":\"/topic/tracking/" + bookingId + "\",\"bookingId\":\"" + bookingId
                        + "\",\"payload\":{\"bookingId\":\"" + UUID.randomUUID() + "\",\"lat\":17.5,\"lng\":78.4,"
                        + "\"timestamp\":" + now + "}}";
                // subscription is asynchronous: publish until the first message is observed
                for (int i = 0; i < 20 && got.getCount() == 2; i++) {
                    sentAt[0] = System.nanoTime();
                    Long receivers = t.convertAndSend(channel, good);
                    if (got.await(500, TimeUnit.MILLISECONDS)) {
                        System.out.println("[upstash-live] PUBLISH delivered to " + receivers + " subscriber(s)");
                    }
                }
                sentAt[0] = System.nanoTime();
                t.convertAndSend(channel, forged);
                assertThat(got.await(10, TimeUnit.SECONDS)).as("both messages received through Upstash pub/sub").isTrue();

                System.out.println("[upstash-live] pub/sub publish->receive ms = " + latencyMs);
                assertThat(relayed.get(0)).as("valid fix relayed to STOMP").isTrue();
                assertThat(consumed.get(0)).as("valid fix accepted by the geofence/history consumer").isTrue();
                assertThat(relayed.get(relayed.size() - 1)).as("forged cross-booking frame dropped by bridge").isFalse();
                assertThat(consumed.get(consumed.size() - 1)).as("forged cross-booking frame dropped by consumer").isFalse();

                ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
                verify(stomp, timeout(2_000).atLeastOnce())
                        .convertAndSend(eq("/topic/tracking/" + bookingId), payload.capture());
                assertThat(mapper.valueToTree(payload.getValue()).path("lat").asDouble()).isEqualTo(17.4486);
                verify(arrival, timeout(2_000).atLeastOnce()).onFix(eq(bookingId), any(GpsFix.class));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                container.stop();
                try {
                    container.destroy();
                } catch (Exception ignored) {
                    // best effort
                }
            }
        });
    }
}
