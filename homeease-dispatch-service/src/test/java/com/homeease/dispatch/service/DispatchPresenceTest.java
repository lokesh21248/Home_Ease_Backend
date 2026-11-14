package com.homeease.dispatch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.homeease.dispatch.dto.DispatchDto.TriggerDispatchRequest;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

/** The dispatch service must not report candidates that have no live heartbeat. */
class DispatchPresenceTest {

    private RedisTemplate<String, String> redis;
    private GeoOperations<String, String> geo;
    private ValueOperations<String, String> values;
    private DispatchEngineService svc;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(RedisTemplate.class);
        geo = mock(GeoOperations.class);
        values = mock(ValueOperations.class);
        when(redis.opsForGeo()).thenReturn(geo);
        when(redis.opsForValue()).thenReturn(values);
        svc = new DispatchEngineService(redis);
        ReflectionTestUtils.setField(svc, "radiusMeters", 5000d);
        ReflectionTestUtils.setField(svc, "enforcePresence", true);
    }

    private GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults(String... ids) {
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = Arrays.stream(ids)
                .map(id -> new GeoResult<>(new RedisGeoCommands.GeoLocation<>(id, new Point(77.5, 12.9)), new Distance(1)))
                .toList();
        return new GeoResults<>(list);
    }

    private String status() {
        return svc.triggerSpatialMatching(TriggerDispatchRequest.builder()
                .bookingId(UUID.randomUUID()).userLat(12.9).userLng(77.5).build()).getStatus();
    }

    @Test
    void geo_member_without_heartbeat_is_not_a_candidate() {
        when(geo.radius(any(String.class), any(Circle.class))).thenReturn(geoResults("w-stale"));
        when(values.multiGet(anyList())).thenReturn(Arrays.asList((String) null));
        assertThat(status()).isEqualTo("WORKER_NOT_FOUND");
    }

    @Test
    void one_live_worker_among_stale_ones_is_enough_and_heartbeat_keys_use_the_shared_contract() {
        when(geo.radius(any(String.class), any(Circle.class))).thenReturn(geoResults("w-stale", "w-live"));
        when(values.multiGet(anyList())).thenReturn(Arrays.asList(null, "ONLINE"));
        assertThat(status()).isEqualTo("CANDIDATES_ALERTED");
        verify(values).multiGet(List.of("worker:heartbeat:w-stale", "worker:heartbeat:w-live")); // key names preserved, ONE command
    }

    @Test
    void rollback_switch_restores_previous_behaviour_without_touching_heartbeats() {
        ReflectionTestUtils.setField(svc, "enforcePresence", false);
        when(geo.radius(any(String.class), any(Circle.class))).thenReturn(geoResults("w-stale"));
        assertThat(status()).isEqualTo("CANDIDATES_ALERTED");
        verify(values, never()).multiGet(anyList());
    }

    @Test
    void empty_geo_result_means_no_candidates() {
        when(geo.radius(any(String.class), any(Circle.class))).thenReturn(geoResults());
        assertThat(status()).isEqualTo("WORKER_NOT_FOUND");
    }

    @Test
    void redis_failure_is_reported_as_no_candidates_without_leaking_the_error_text() {
        when(geo.radius(any(String.class), any(Circle.class))).thenThrow(new IllegalStateException("rediss://default:SECRET@host"));
        assertThat(status()).isEqualTo("WORKER_NOT_FOUND");
    }
}
