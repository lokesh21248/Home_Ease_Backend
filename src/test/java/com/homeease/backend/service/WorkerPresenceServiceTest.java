package com.homeease.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.fppt.jedismock.RedisServer;
import com.homeease.backend.model.entity.Worker;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Presence-model tests against an in-process, pure-Java Redis server (real RESP protocol, real Lettuce client,
 * real TTL expiry and Lua execution). The mock lacks GEOADD, so GEO sets are seeded as plain sorted sets, which is
 * exactly what they are in Redis; GEOADD/GEORADIUS themselves are covered by the Upstash-gated live tests.
 */
class WorkerPresenceServiceTest {

    private RedisServer server;
    private LettuceConnectionFactory factory;
    private RedisTemplate<String, String> redis;
    private WorkerPresenceService presence;

    @BeforeEach
    void start() throws Exception {
        server = RedisServer.newRedisServer().start();
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(server.getHost(), server.getBindPort()));
        factory.afterPropertiesSet();
        redis = template(factory);
        // interval 1s / ttl 3s keeps expiry tests fast while still satisfying ttl >= 3 x interval
        presence = new WorkerPresenceService(redis, 1, 3, true, 300);
    }

    @AfterEach
    void stop() throws Exception {
        factory.destroy();
        try {
            server.stop();
        } catch (Exception alreadyStoppedByTheTest) {
            // the Redis-outage test stops the server itself
        }
    }

    private static RedisTemplate<String, String> template(LettuceConnectionFactory f) {
        RedisTemplate<String, String> t = new RedisTemplate<>();
        t.setConnectionFactory(f);
        StringRedisSerializer s = new StringRedisSerializer();
        t.setKeySerializer(s);
        t.setValueSerializer(s);
        t.setHashKeySerializer(s);
        t.setHashValueSerializer(s);
        t.afterPropertiesSet();
        return t;
    }

    @Test
    void policy_is_validated_at_startup() {
        assertThatThrownBy(() -> new WorkerPresenceService(redis, 15, 30, true, 300))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WORKER_HEARTBEAT_TTL_SECONDS")
                .hasMessageContaining(">= 3 x");
        assertThatThrownBy(() -> new WorkerPresenceService(redis, 0, 60, true, 300))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new WorkerPresenceService(redis, 15, 60, true, 300).heartbeatTtl()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void refreshed_heartbeat_has_the_configured_ttl_and_expires_so_a_silent_worker_becomes_unavailable() throws Exception {
        UUID w = UUID.randomUUID();
        presence.refreshHeartbeat(w, "ONLINE");
        assertThat(presence.isAlive(w)).isTrue();
        Long ttl = redis.getExpire(WorkerPresenceService.HEARTBEAT_PREFIX + w);
        assertThat(ttl).isBetween(1L, 3L);
        assertThat(redis.getExpire(WorkerPresenceService.LAST_LOCATION_PREFIX + w)).isBetween(1L, 3L);

        Thread.sleep(3500);
        assertThat(presence.isAlive(w)).as("heartbeat must expire after the TTL with no further pings").isFalse();
    }

    @Test
    void a_later_ping_keeps_the_worker_alive_past_the_original_ttl() throws Exception {
        UUID w = UUID.randomUUID();
        presence.refreshHeartbeat(w, "ONLINE");
        Thread.sleep(2000);
        presence.refreshHeartbeat(w, "ONLINE"); // normal 1s-cadence ping, here deliberately late by 1s
        Thread.sleep(2000);                      // 4s after the first write, but only 2s after the refresh
        assertThat(presence.isAlive(w)).isTrue();
    }

    @Test
    void retainAlive_uses_the_heartbeat_and_ignores_absent_workers() {
        UUID alive = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        presence.refreshHeartbeat(alive, "ONLINE");
        assertThat(presence.retainAlive(List.of(alive, gone, alive))).containsExactly(alive);
        assertThat(presence.retainAlive(List.of())).isEmpty();
    }

    @Test
    void markOffline_removes_the_worker_from_every_geo_set_and_deletes_heartbeats_but_not_other_workers() {
        UUID w = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        String zone = "560001";
        for (String key : List.of(WorkerPresenceService.GEO_ACTIVE, WorkerPresenceService.GEO_GLOBAL,
                WorkerPresenceService.GEO_ZONE_PREFIX + zone)) {
            redis.opsForZSet().add(key, w.toString(), 1.0);
            redis.opsForZSet().add(key, other.toString(), 2.0);
        }
        presence.refreshHeartbeat(w, zone);      // zone recorded as the heartbeat value, as WorkerStreamService did
        presence.refreshHeartbeat(other, "ONLINE");

        presence.markOffline(w);

        for (String key : List.of(WorkerPresenceService.GEO_ACTIVE, WorkerPresenceService.GEO_GLOBAL,
                WorkerPresenceService.GEO_ZONE_PREFIX + zone)) {
            assertThat(redis.opsForZSet().range(key, 0, -1)).containsExactly(other.toString());
        }
        assertThat(presence.isAlive(w)).isFalse();
        assertThat(redis.hasKey(WorkerPresenceService.LAST_LOCATION_PREFIX + w)).isFalse();
        assertThat(presence.isAlive(other)).isTrue();
    }

    @Test
    void sweep_removes_only_members_without_a_heartbeat_and_never_deletes_keys() {
        UUID live = UUID.randomUUID();
        UUID stale1 = UUID.randomUUID();
        UUID stale2 = UUID.randomUUID();
        String zoneKey = WorkerPresenceService.GEO_ZONE_PREFIX + "500010";
        for (String key : List.of(WorkerPresenceService.GEO_ACTIVE, WorkerPresenceService.GEO_GLOBAL, zoneKey)) {
            redis.opsForZSet().add(key, live.toString(), 1.0);
            redis.opsForZSet().add(key, stale1.toString(), 2.0);
            redis.opsForZSet().add(key, stale2.toString(), 3.0);
        }
        presence.refreshHeartbeat(live, "ONLINE");

        presence.setSweepZoneSets(true);
        int removed = presence.sweepStaleMembers();

        assertThat(removed).isEqualTo(6); // 2 stale members x 3 sets
        for (String key : List.of(WorkerPresenceService.GEO_ACTIVE, WorkerPresenceService.GEO_GLOBAL, zoneKey)) {
            assertThat(redis.opsForZSet().range(key, 0, -1)).containsExactly(live.toString());
        }
        assertThat(presence.isAlive(live)).isTrue();
        assertThat(presence.sweepStaleMembers()).as("idempotent").isZero();
    }

    @Test
    void zone_sets_are_not_swept_by_default_to_bound_command_usage() {
        UUID stale = UUID.randomUUID();
        String zoneKey = WorkerPresenceService.GEO_ZONE_PREFIX + "500010";
        redis.opsForZSet().add(zoneKey, stale.toString(), 1.0);
        redis.opsForZSet().add(WorkerPresenceService.GEO_ACTIVE, stale.toString(), 1.0);

        assertThat(presence.sweepStaleMembers()).isEqualTo(1); // only active_workers_geo / geo:workers:global
        assertThat(redis.opsForZSet().range(zoneKey, 0, -1)).containsExactly(stale.toString());
    }

    @Test
    void sweep_handles_batches_larger_than_one_script_call() {
        UUID live = UUID.randomUUID();
        redis.opsForZSet().add(WorkerPresenceService.GEO_ACTIVE, live.toString(), 1.0);
        presence.refreshHeartbeat(live, "ONLINE");
        for (int i = 0; i < 250; i++) {
            redis.opsForZSet().add(WorkerPresenceService.GEO_ACTIVE, UUID.randomUUID().toString(), 2.0 + i);
        }
        assertThat(presence.sweepStaleMembers()).isEqualTo(250);
        assertThat(redis.opsForZSet().range(WorkerPresenceService.GEO_ACTIVE, 0, -1)).containsExactly(live.toString());
    }

    @Test
    void dispatchable_requires_a_live_heartbeat_when_enforced() {
        Worker w = Worker.builder().workerId(UUID.randomUUID()).isOnline(true)
                .lastLocationUpdatedAt(Instant.now()).build(); // DB says "fresh", Redis says nothing
        assertThat(presence.isDispatchable(w)).as("no heartbeat => not dispatchable even if DB looks fresh").isFalse();
        presence.refreshHeartbeat(w.getWorkerId(), "ALIVE");
        assertThat(presence.isDispatchable(w)).isTrue();
        assertThat(presence.isDispatchable(null)).isFalse();
    }

    @Test
    void enforcement_can_be_switched_off_for_rollback() {
        WorkerPresenceService off = new WorkerPresenceService(redis, 1, 3, false, 300);
        Worker w = Worker.builder().workerId(UUID.randomUUID()).isOnline(true).build();
        assertThat(off.isDispatchable(w)).isTrue();
        assertThat(off.sweepStaleMembers()).isZero(); // nothing to sweep, and scheduledSweep() is a no-op when off
    }

    @Test
    void when_redis_is_down_the_db_timestamp_decides_using_the_same_threshold() throws Exception {
        Worker fresh = Worker.builder().workerId(UUID.randomUUID()).lastLocationUpdatedAt(Instant.now().minusSeconds(1)).build();
        Worker stale = Worker.builder().workerId(UUID.randomUUID()).lastLocationUpdatedAt(Instant.now().minusSeconds(3600)).build();
        Worker never = Worker.builder().workerId(UUID.randomUUID()).build();
        server.stop(); // Redis outage
        assertThat(presence.isDispatchable(fresh)).isTrue();
        assertThat(presence.isDispatchable(stale)).isFalse();
        assertThat(presence.isDispatchable(never)).isFalse();
    }

    @Test
    void coordinate_validation_rejects_missing_nan_out_of_range_and_null_island() {
        assertThat(WorkerPresenceService.isUsableCoordinate(12.97, 77.59)).isTrue();
        assertThat(WorkerPresenceService.isUsableCoordinate(null, 77.59)).isFalse();
        assertThat(WorkerPresenceService.isUsableCoordinate(Double.NaN, 77.59)).isFalse();
        assertThat(WorkerPresenceService.isUsableCoordinate(91.0, 77.59)).isFalse();
        assertThat(WorkerPresenceService.isUsableCoordinate(12.0, 181.0)).isFalse();
        assertThat(WorkerPresenceService.isUsableCoordinate(0.0, 0.0)).isFalse();
    }

    // ------------------------------------------------ stationary optimisation (opt-in, 1 command instead of 7)

    private static final double LAT = 12.9716;
    private static final double LNG = 77.5946;

    @Test
    void stationary_optimisation_is_off_by_default() {
        UUID w = UUID.randomUUID();
        presence.recordFullRegistration(w, LAT, LNG);
        assertThat(presence.tryLightweightRefresh(w, LAT, LNG, "ONLINE")).isFalse();
        assertThat(presence.isAlive(w)).as("nothing was written").isFalse();
    }

    @Test
    void a_stationary_worker_after_a_full_registration_refreshes_only_the_heartbeat() {
        presence.configureStationary(true, 25.0, 3);
        UUID w = UUID.randomUUID();
        assertThat(presence.tryLightweightRefresh(w, LAT, LNG, "560001")).as("no full registration yet").isFalse();

        presence.recordFullRegistration(w, LAT, LNG);
        assertThat(presence.tryLightweightRefresh(w, LAT + 0.00005, LNG, "560001")).as("~5 m jitter").isTrue();

        assertThat(presence.isAlive(w)).isTrue();
        assertThat(redis.opsForValue().get(WorkerPresenceService.HEARTBEAT_PREFIX + w)).isEqualTo("560001");
        assertThat(redis.getExpire(WorkerPresenceService.HEARTBEAT_PREFIX + w)).isBetween(1L, 3L);
        assertThat(redis.hasKey(WorkerPresenceService.LAST_LOCATION_PREFIX + w))
                .as("the position timestamp is NOT rewritten by a heartbeat-only refresh").isFalse();
    }

    @Test
    void a_worker_who_moved_beyond_the_threshold_gets_the_full_path() {
        presence.configureStationary(true, 25.0, 3);
        UUID w = UUID.randomUUID();
        presence.recordFullRegistration(w, LAT, LNG);
        assertThat(presence.tryLightweightRefresh(w, LAT + 0.001, LNG, "ONLINE")).as("~111 m").isFalse();
        assertThat(presence.isAlive(w)).isFalse();
    }

    @Test
    void the_full_registration_is_repeated_once_the_refresh_window_has_elapsed() throws Exception {
        presence.configureStationary(true, 25.0, 1);
        UUID w = UUID.randomUUID();
        presence.recordFullRegistration(w, LAT, LNG);
        assertThat(presence.tryLightweightRefresh(w, LAT, LNG, "ONLINE")).isTrue();
        Thread.sleep(1200);
        assertThat(presence.tryLightweightRefresh(w, LAT, LNG, "ONLINE")).as("window elapsed -> full path").isFalse();
    }

    @Test
    void going_offline_forgets_the_registration_so_a_stray_ping_cannot_revive_the_worker() {
        presence.configureStationary(true, 25.0, 3);
        UUID w = UUID.randomUUID();
        presence.recordFullRegistration(w, LAT, LNG);
        presence.markOffline(w);
        assertThat(presence.tryLightweightRefresh(w, LAT, LNG, "ONLINE")).isFalse();
        assertThat(presence.isAlive(w)).isFalse();
    }

    @Test
    void a_redis_failure_surfaces_so_the_caller_can_fall_back_to_the_full_path() throws Exception {
        presence.configureStationary(true, 25.0, 3);
        UUID w = UUID.randomUUID();
        presence.recordFullRegistration(w, LAT, LNG);
        server.stop();
        assertThatThrownBy(() -> presence.tryLightweightRefresh(w, LAT, LNG, "ONLINE"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void invalid_stationary_settings_are_rejected_at_startup() {
        assertThatThrownBy(() -> presence.configureStationary(true, 0.0, 3))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("WORKER_PING_STATIONARY_METERS");
        assertThatThrownBy(() -> presence.configureStationary(true, 25.0, 0))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("FULL_REFRESH_SECONDS");
        assertThatThrownBy(() -> presence.configureStationary(true, 25.0, 100))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("4 x WORKER_HEARTBEAT_TTL_SECONDS");
        presence.configureStationary(false, 0.0, 0); // invalid values are irrelevant while disabled
    }
}
