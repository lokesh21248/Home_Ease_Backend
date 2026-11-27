package com.homeease.tracking.service;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.tracking.dto.LocationPayload;
import com.homeease.tracking.exception.RateLimitExceededException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Ingest hardening: (1) moving does not bypass the rate limiter any more, (2) a GPS frame never carries an invented
 * distance/ETA, even when the assignment record holds the customer's coordinates.
 */
@SuppressWarnings("unchecked")
class LiveTrackingIngestHardeningTest {

    private LiveTrackingService service;
    private ValueOperations<String, String> ops;

    @BeforeEach
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        lenient().when(redis.opsForValue()).thenReturn(ops);
        lenient().when(redis.opsForGeo()).thenReturn(mock(GeoOperations.class));
        service = new LiveTrackingService(redis, new ObjectMapper(), mock(SimpMessagingTemplate.class));
        ReflectionTestUtils.setField(service, "windowSeconds", 60);
        ReflectionTestUtils.setField(service, "maxUpdates", 30);
        ReflectionTestUtils.setField(service, "movementThresholdMeters", 15.0);
        ReflectionTestUtils.setField(service, "maxAccuracyMeters", 50.0);
        ReflectionTestUtils.setField(service, "requireAssignment", false);
        ReflectionTestUtils.setField(service, "hardMaxUpdatesPerMinute", 60);
        service.init();
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void movementNoLongerBypassesTheRateLimit() {
        long t = System.currentTimeMillis() - 50_000;
        double lat = 17.40;
        // 60 frames, each 20 m apart (above the 15 m "movement" exemption that used to skip the limiter entirely)
        // and 0.8 s apart (25 m/s, plausible enough not to trip the teleport filter)
        for (int i = 0; i < 60; i++) {
            LocationPayload p = LocationPayload.builder().bookingId("BK-SPAM").lat(lat).lng(78.40)
                    .accuracy(5.0).timestamp(t).build();
            service.processLocationUpdate("BK-SPAM", p, "WRK-SPAM");
            lat += 20.0 / 111_195.0;
            t += 800;
        }
        LocationPayload sixtyFirst = LocationPayload.builder().bookingId("BK-SPAM").lat(lat).lng(78.40)
                .accuracy(5.0).timestamp(t).build();
        assertThrows(RateLimitExceededException.class,
                () -> service.processLocationUpdate("BK-SPAM", sixtyFirst, "WRK-SPAM"));
    }

    @Test
    void gpsFrameCarriesNoInventedEta() {
        lenient().when(ops.get("tracking:assignment:BK-ETA")).thenReturn(
                "{\"status\":\"ON_THE_WAY\",\"trackingActive\":true,\"userId\":\"WRK-1\",\"customerLat\":17.45,\"customerLng\":78.40}");
        LocationPayload p = LocationPayload.builder().bookingId("BK-ETA").lat(17.40).lng(78.40)
                .accuracy(5.0).timestamp(System.currentTimeMillis()).build();
        LocationPayload out = service.processLocationUpdate("BK-ETA", p, "WRK-1");
        assertNull(out.getEtaMinutes(), "no assumed-speed ETA on a GPS frame");
        assertNull(out.getDistanceKm(), "no straight-line distance presented as route distance");
    }
}
