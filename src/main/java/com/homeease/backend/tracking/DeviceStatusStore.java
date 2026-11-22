package com.homeease.backend.tracking;

import com.homeease.backend.tracking.TrackingPhaseResolver.DeviceGpsState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * The partner app's own report of its location capability (permission denied / GPS switched off / OK), kept for a
 * short TTL so an old report can never explain a current outage.
 */
@Component
public class DeviceStatusStore {

    static final String KEY_PREFIX = "tracking:device:";
    static final Duration TTL = Duration.ofSeconds(180);
    private static final Logger log = LoggerFactory.getLogger(DeviceStatusStore.class);

    private final RedisTemplate<String, String> redisTemplate;

    public DeviceStatusStore(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void report(UUID bookingId, DeviceGpsState state) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + bookingId, state.name(), TTL);
        } catch (RuntimeException e) {
            log.warn("Could not store device GPS status for booking {}: {}", bookingId, e.getClass().getSimpleName());
        }
    }

    public DeviceGpsState read(UUID bookingId) {
        try {
            String v = redisTemplate.opsForValue().get(KEY_PREFIX + bookingId);
            return v == null ? DeviceGpsState.UNKNOWN : DeviceGpsState.valueOf(v);
        } catch (RuntimeException e) {
            return DeviceGpsState.UNKNOWN;
        }
    }
}
