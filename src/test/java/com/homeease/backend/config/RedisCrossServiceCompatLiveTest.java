package com.homeease.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Cross-service Redis compatibility, using the main backend's real {@link RedisConfig} template
 * (String serializers) against the Redis in REDIS_URL. SKIPPED unless REDIS_URL is set.
 *
 * <p>This replays the exact Redis commands the services issue (WorkerService / WorkerStreamService
 * online-offline + heartbeat, the dispatch service's NEUTRAL-metre radius query, the tracking service's
 * key writes and pub/sub publish) but on keys/channels prefixed {@code test:compat:<uuid>:}. Production key
 * names and the real {@code ws:broadcast:tracking} channel are deliberately NOT used here, so nothing can
 * leak into live dispatch or reach customers. It is protocol-level compatibility, not an invocation of the
 * other services' classes (see DispatchRedisLiveTest for that, which is opt-in).
 */
class RedisCrossServiceCompatLiveTest {

    private static final String REDIS_URL = System.getenv("REDIS_URL");
    private final String p = "test:compat:" + UUID.randomUUID() + ":";

    private void run(BiConsumer<RedisTemplate<String, String>, RedisConnectionFactory> body) {
        assumeTrue(REDIS_URL != null && !REDIS_URL.isBlank(), "REDIS_URL not set - live test skipped");
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withConfiguration(UserConfigurations.of(RedisConfig.class))
                .withPropertyValues("spring.data.redis.url=" + REDIS_URL,
                        "spring.data.redis.timeout=3000ms", "spring.data.redis.connect-timeout=5000ms")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    @SuppressWarnings("unchecked")
                    RedisTemplate<String, String> t = ctx.getBean("redisTemplate", RedisTemplate.class);
                    body.accept(t, ctx.getBean(RedisConnectionFactory.class));
                });
    }

    @Test
    void worker_online_is_discoverable_by_dispatch_query_and_offline_removes_it() {
        run((t, cf) -> {
            String active = p + "active_workers_geo", global = p + "geo:workers:global";
            String worker = UUID.randomUUID().toString();
            String hb = p + "worker:heartbeat:" + worker;
            try {
                // --- WorkerService: going ONLINE ---
                Point pt = new Point(77.5946, 12.9716);
                t.opsForGeo().add(active, pt, worker);
                t.opsForGeo().add(global, pt, worker);
                t.opsForValue().set(hb, "ALIVE", Duration.ofMinutes(5));
                // a far-away worker that must NOT be returned
                t.opsForGeo().add(active, new Point(72.8777, 19.0760), "far-" + worker);

                // --- dispatch service query: radius 5000 with Metrics.NEUTRAL (metres) ---
                var neutral = t.opsForGeo().radius(active, new Circle(new Point(77.60, 12.97),
                        new Distance(5000d, Metrics.NEUTRAL)));
                System.out.println("[compat] dispatch NEUTRAL 5000m -> " + neutral.getContent().stream()
                        .map(r -> r.getContent().getName().startsWith("far-") ? "far" : "worker").toList());
                assertThat(neutral.getContent()).extracting(r -> r.getContent().getName()).containsExactly(worker);

                // --- WorkerStreamService.findActiveWorkersInZone: KILOMETERS radius + heartbeat filter ---
                var km = t.opsForGeo().radius(active, new Circle(new Point(77.60, 12.97),
                        new Distance(5, Metrics.KILOMETERS)));
                assertThat(km.getContent()).extracting(r -> r.getContent().getName()).containsExactly(worker);
                assertThat(t.hasKey(hb)).isTrue();

                // --- WorkerService: going OFFLINE ---
                t.opsForGeo().remove(active, worker);
                t.opsForGeo().remove(global, worker);
                t.delete(hb);
                var after = t.opsForGeo().radius(active, new Circle(new Point(77.60, 12.97),
                        new Distance(5000d, Metrics.NEUTRAL)));
                assertThat(after.getContent()).isEmpty();
                assertThat(t.hasKey(hb)).isFalse();
            } finally {
                t.delete(active);
                t.delete(global);
                t.delete(hb);
            }
        });
    }

    @Test
    void heartbeat_ttl_expires_while_geo_member_lingers_so_the_heartbeat_filter_is_what_hides_stale_workers() {
        run((t, cf) -> {
            String active = p + "active_workers_geo";
            String worker = UUID.randomUUID().toString();
            String hb = p + "worker:heartbeat:" + worker;
            try {
                t.opsForGeo().add(active, new Point(77.5946, 12.9716), worker);
                // WorkerStreamService uses Duration.ofSeconds(30); shortened here only to keep the test fast
                t.opsForValue().set(hb, "ONLINE", Duration.ofSeconds(3));
                Long ttl = t.getExpire(hb, TimeUnit.SECONDS);
                System.out.println("[compat] heartbeat TTL after set = " + ttl + "s");
                assertThat(ttl).isBetween(1L, 3L);
                Thread.sleep(4000);
                boolean heartbeatAlive = Boolean.TRUE.equals(t.hasKey(hb));
                var stillInGeo = t.opsForGeo().radius(active, new Circle(new Point(77.5946, 12.9716),
                        new Distance(1, Metrics.KILOMETERS)));
                System.out.println("[compat] after 4s: heartbeat alive=" + heartbeatAlive
                        + ", still in GEO set=" + !stillInGeo.getContent().isEmpty());
                assertThat(heartbeatAlive).isFalse();
                assertThat(stillInGeo.getContent()).hasSize(1); // GEO members never expire on their own
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                t.delete(active);
                t.delete(hb);
            }
        });
    }

    @Test
    void tracking_style_json_and_core_style_deletes_interoperate() {
        run((t, cf) -> {
            String tracking = p + "tracking:booking:B9", assignment = p + "tracking:assignment:B9";
            try {
                t.opsForValue().set(tracking, "{\"lat\":12.9,\"lng\":77.5}", Duration.ofHours(2));
                t.opsForValue().set(assignment, "{\"status\":\"ACCEPTED\",\"workerId\":\"w\"}", Duration.ofHours(24));
                assertThat(t.opsForValue().get(tracking)).contains("\"lat\":12.9");
                assertThat(t.getExpire(assignment, TimeUnit.HOURS)).isBetween(23L, 24L);
                // BookingStateMachine / BookingCancellationService delete the tracking keys on completion
                assertThat(t.delete(tracking)).isTrue();
                assertThat(t.opsForValue().get(tracking)).isNull();
            } finally {
                t.delete(tracking);
                t.delete(assignment);
            }
        });
    }

    @Test
    void tracking_publish_reaches_core_bridge_style_listener_on_the_same_channel_semantics() throws Exception {
        String channel = p + "ws:broadcast:tracking";
        CountDownLatch got = new CountDownLatch(1);
        AtomicReference<String> body = new AtomicReference<>();
        run((t, cf) -> {
            RedisMessageListenerContainer container = new RedisMessageListenerContainer();
            container.setConnectionFactory(cf);
            container.addMessageListener((message, pattern) -> {
                body.set(new String(message.getBody(), StandardCharsets.UTF_8));
                got.countDown();
            }, new ChannelTopic(channel));
            container.afterPropertiesSet();
            container.start();
            try {
                String msg = "{\"destination\":\"/topic/tracking/B1\",\"bookingId\":\"B1\",\"payload\":{\"lat\":1.5}}";
                for (int i = 0; i < 20 && got.getCount() > 0; i++) {
                    t.convertAndSend(channel, msg);
                    got.await(500, TimeUnit.MILLISECONDS);
                }
                assertThat(got.getCount()).isZero();
                assertThat(body.get()).isEqualTo(msg);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                container.stop();
                try { container.destroy(); } catch (Exception ignored) { }
            }
        });
    }

    @Test
    void presence_cleanup_lua_runs_on_the_target_redis_and_removes_only_members_without_a_heartbeat() {
        run((t, cf) -> {
            // isolated keys only: the real sweep acts on SHARED keys and must never run in a test
            String geo = p + "geo", live = p + "live", stale = p + "stale";
            String hbLive = p + "hb:" + live;
            try {
                t.opsForZSet().add(geo, live, 1.0);
                t.opsForZSet().add(geo, stale, 2.0);
                t.opsForValue().set(hbLive, "ONLINE", Duration.ofSeconds(60));
                Long removed = t.execute(
                        new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                                com.homeease.backend.service.WorkerPresenceService.CLEANUP_LUA, Long.class),
                        java.util.List.of(geo, hbLive, p + "hb:" + stale), live, stale);
                System.out.println("[compat] Lua cleanup removed=" + removed);
                assertThat(removed).isEqualTo(1L);
                assertThat(t.opsForZSet().range(geo, 0, -1)).containsExactly(live);
            } finally {
                t.delete(geo);
                t.delete(hbLive);
            }
        });
    }

    @Test
    void worker_presence_service_registers_and_removes_one_unique_worker_on_the_real_keys_opt_in() {
        assumeTrue("true".equalsIgnoreCase(System.getenv("REDIS_LIVE_ALLOW_SHARED_KEYS")),
                "REDIS_LIVE_ALLOW_SHARED_KEYS!=true - shared-key test skipped");
        run((t, cf) -> {
            com.homeease.backend.service.WorkerPresenceService ps =
                    new com.homeease.backend.service.WorkerPresenceService(t, 15, 60, true, 300);
            UUID w = UUID.randomUUID();
            try {
                ps.registerPresence(w, -79.9, 0.1, null, "ONLINE"); // Antarctica: cannot match a real booking
                Long ttl = t.getExpire("worker:heartbeat:" + w);
                System.out.println("[compat] registerPresence heartbeat TTL=" + ttl + "s");
                assertThat(ps.isAlive(w)).isTrue();
                assertThat(ttl).isBetween(1L, 60L);
                var found = t.opsForGeo().radius("active_workers_geo",
                        new Circle(new Point(0.1, -79.9), new Distance(5000d, Metrics.NEUTRAL)));
                assertThat(found.getContent()).extracting(r -> r.getContent().getName()).contains(w.toString());
                ps.markOffline(w);
                assertThat(ps.isAlive(w)).isFalse();
                assertThat(t.opsForGeo().radius("active_workers_geo",
                        new Circle(new Point(0.1, -79.9), new Distance(5000d, Metrics.NEUTRAL)))
                        .getContent()).extracting(r -> r.getContent().getName()).doesNotContain(w.toString());
            } finally {
                ps.markOffline(w); // removes only this unique worker
            }
        });
    }

    @Test
    void a_worker_whose_heartbeat_expired_is_rejected_while_its_geo_member_lingers_opt_in() {
        assumeTrue("true".equalsIgnoreCase(System.getenv("REDIS_LIVE_ALLOW_SHARED_KEYS")),
                "REDIS_LIVE_ALLOW_SHARED_KEYS!=true - shared-key test skipped");
        run((t, cf) -> {
            // 3 s TTL for the test only (the production default is 60 s); still satisfies TTL >= 3 x interval
            com.homeease.backend.service.WorkerPresenceService ps =
                    new com.homeease.backend.service.WorkerPresenceService(t, 1, 3, true, 900);
            UUID w = UUID.randomUUID();
            Circle around = new Circle(new Point(0.1, -79.9), new Distance(5000d, Metrics.NEUTRAL));
            try {
                ps.registerPresence(w, -79.9, 0.1, null, "ONLINE"); // Antarctica: cannot match a real booking
                assertThat(ps.retainAlive(java.util.List.of(w))).as("alive right after a ping").contains(w);

                Thread.sleep(4500);

                assertThat(ps.retainAlive(java.util.List.of(w))).as("stale: heartbeat expired").isEmpty();
                boolean stillInGeo = t.opsForGeo().radius("active_workers_geo", around).getContent().stream()
                        .anyMatch(r -> r.getContent().getName().equals(w.toString()));
                System.out.println("[compat] after expiry: alive=false, still in GEO set=" + stillInGeo
                        + " (this is why every reader must filter on the heartbeat)");
                assertThat(stillInGeo).as("GEO members do not expire by themselves").isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                ps.markOffline(w); // removes only this unique worker and its two keys
            }
            assertThat(t.opsForGeo().radius("active_workers_geo", around).getContent().stream()
                    .anyMatch(r -> r.getContent().getName().equals(w.toString()))).as("cleaned up").isFalse();
        });
    }

}
