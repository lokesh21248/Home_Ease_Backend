package com.homeease.tracking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** GEO members never expire: the nearby-workers query must hide workers whose heartbeat is gone. */
class NearestWorkersPresenceTest {

    @SuppressWarnings("unchecked")
    private LiveTrackingService serviceWith(boolean enforce, List<String> heartbeatValues, String... geoIds) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        GeoOperations<String, String> geo = mock(GeoOperations.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForGeo()).thenReturn(geo);
        when(redis.opsForValue()).thenReturn(values);
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = Arrays.stream(geoIds)
                .map(id -> new GeoResult<>(new RedisGeoCommands.GeoLocation<>(id, new Point(77.5, 12.9)), new Distance(1.2)))
                .toList();
        when(geo.radius(any(String.class), any(Circle.class), any(RedisGeoCommands.GeoRadiusCommandArgs.class)))
                .thenReturn(new GeoResults<>(list));
        when(values.multiGet(anyList())).thenReturn(heartbeatValues);
        LiveTrackingService svc = new LiveTrackingService(redis, new ObjectMapper(), mock(SimpMessagingTemplate.class));
        ReflectionTestUtils.setField(svc, "enforcePresence", enforce);
        return svc;
    }

    @Test
    void only_workers_with_a_live_heartbeat_are_returned() {
        LiveTrackingService svc = serviceWith(true, Arrays.asList(null, "ONLINE", null), "stale-1", "live-1", "stale-2");
        List<Map<String, Object>> out = svc.findNearestWorkers(12.9, 77.5, 5.0, 10);
        assertThat(out).extracting(m -> m.get("workerId")).containsExactly("live-1");
        svc.shutdown();
    }

    @Test
    void switching_enforcement_off_restores_the_previous_unfiltered_result() {
        LiveTrackingService svc = serviceWith(false, Arrays.asList((String) null, (String) null), "stale-1", "stale-2");
        assertThat(svc.findNearestWorkers(12.9, 77.5, 5.0, 10)).hasSize(2);
        svc.shutdown();
    }
}
