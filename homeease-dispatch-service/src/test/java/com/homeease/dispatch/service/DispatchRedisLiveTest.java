package com.homeease.dispatch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.homeease.dispatch.dto.DispatchDto.DispatchResultResponse;
import com.homeease.dispatch.dto.DispatchDto.TriggerDispatchRequest;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Live check of the REAL {@link DispatchEngineService} against the Redis in REDIS_URL.
 *
 * <p>SKIPPED unless REDIS_URL is set. The service hard-codes the shared GEO key
 * {@code active_workers_geo} (key names must not change), so seeding it touches a shared key. To
 * keep production data safe this test is additionally opt-in via {@code REDIS_LIVE_ALLOW_SHARED_KEYS=true}.
 * When enabled it adds ONE uniquely-named member at a remote coordinate (Antarctica, ~0 chance of
 * matching a real booking) and removes only that member in a finally block - never the key, never FLUSH.
 */
class DispatchRedisLiveTest {

    private static final String REDIS_URL = System.getenv("REDIS_URL");
    private static final boolean ALLOW_SHARED = "true".equalsIgnoreCase(System.getenv("REDIS_LIVE_ALLOW_SHARED_KEYS"));

    @BeforeEach
    void gate() {
        assumeTrue(REDIS_URL != null && !REDIS_URL.isBlank(), "REDIS_URL not set - live test skipped");
    }

    private void withRedis(Consumer<StringRedisTemplate> body) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withPropertyValues("spring.data.redis.url=" + REDIS_URL,
                        "spring.data.redis.timeout=3000ms", "spring.data.redis.connect-timeout=5000ms")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    body.accept(ctx.getBean(StringRedisTemplate.class));
                });
    }

    @Test
    void authenticated_ping() {
        withRedis(t -> assertThat(t.execute((org.springframework.data.redis.core.RedisCallback<String>) c -> c.ping()))
                .isEqualToIgnoringCase("PONG"));
    }

    @Test
    void real_dispatch_service_discovers_a_worker_registered_the_way_the_backend_registers_it() {
        assumeTrue(ALLOW_SHARED, "REDIS_LIVE_ALLOW_SHARED_KEYS!=true - shared-key test skipped");
        withRedis(t -> {
            String workerId = "test-compat-" + UUID.randomUUID();
            Point remote = new Point(0.1, -79.9); // lng, lat - Antarctica
            String heartbeatKey = "worker:heartbeat:" + workerId;
            try {
                DispatchEngineService svc = new DispatchEngineService((RedisTemplate<String, String>) (RedisTemplate<?, ?>) t);
                ReflectionTestUtils.setField(svc, "radiusMeters", 5000d);
                TriggerDispatchRequest request = TriggerDispatchRequest.builder()
                        .bookingId(UUID.randomUUID()).userLat(-79.9).userLng(0.1).build();

                // same call WorkerStreamService / WorkerService make: GEO member WITHOUT heartbeat = stale worker
                t.opsForGeo().add("active_workers_geo", remote, workerId);
                DispatchResultResponse stale = svc.triggerSpatialMatching(request);
                System.out.println("[dispatch-live] member without heartbeat -> " + stale.getStatus());
                assertThat(stale.getStatus()).isEqualTo("WORKER_NOT_FOUND");

                // now the worker pings: heartbeat key with the configured TTL (60s default)
                t.opsForValue().set(heartbeatKey, "ONLINE", java.time.Duration.ofSeconds(60));
                DispatchResultResponse live = svc.triggerSpatialMatching(request);
                System.out.println("[dispatch-live] member with heartbeat -> " + live.getStatus());
                assertThat(live.getStatus()).isEqualTo("CANDIDATES_ALERTED");
            } finally {
                t.opsForGeo().remove("active_workers_geo", workerId); // only our own member
                t.delete(heartbeatKey);                               // only our own key
            }
        });
    }
}
