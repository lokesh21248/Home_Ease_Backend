package com.homeease.tracking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.tracking.dto.LocationPayload;
import com.homeease.tracking.exception.RateLimitExceededException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class LiveTrackingServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private GeoOperations<String, String> geoOperations;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private ObjectMapper objectMapper;
    private LiveTrackingService trackingService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(redisTemplate.opsForGeo()).thenReturn(geoOperations);

        trackingService = new LiveTrackingService(redisTemplate, objectMapper, messagingTemplate);

        ReflectionTestUtils.setField(trackingService, "windowSeconds", 60);
        ReflectionTestUtils.setField(trackingService, "maxUpdates", 5);
        ReflectionTestUtils.setField(trackingService, "movementThresholdMeters", 15.0);
        ReflectionTestUtils.setField(trackingService, "maxAccuracyMeters", 50.0);

        trackingService.init();
    }

    @AfterEach
    void tearDown() {
        trackingService.shutdown();
    }

    @Test
    void testProcessLocationUpdate_ValidUpdate_StoredInL1CacheAndBroadcasted() {
        String bookingId = "BK-100";
        LocationPayload payload = LocationPayload.builder()
                .bookingId(bookingId)
                .partnerId("WRK-1")
                .lat(17.4485)
                .lng(78.3748)
                .bearing(45.0)
                .speed(10.5)
                .timestamp(System.currentTimeMillis())
                .build();

        LocationPayload result = trackingService.processLocationUpdate(bookingId, payload, "WRK-1");

        assertNotNull(result);
        assertEquals(17.4485, result.getLat());
        assertEquals(78.3748, result.getLng());
        assertTrue(result.getTrackingActive());

        // Verify instant L1 retrieval in O(1)
        LocationPayload cached = trackingService.getLatestLocation(bookingId);
        assertNotNull(cached);
        assertEquals(17.4485, cached.getLat());
        assertEquals(78.3748, cached.getLng());

        // Verify WebSocket broadcast
        verify(messagingTemplate).convertAndSend(eq("/topic/tracking/" + bookingId), any(LocationPayload.class));
        verify(messagingTemplate).convertAndSend(eq("/topic/track/" + bookingId), any(LocationPayload.class));
    }

    @Test
    void testProcessLocationUpdate_StaleTimestamp_Rejected() {
        String bookingId = "BK-STALE";
        long t1 = System.currentTimeMillis();

        LocationPayload first = LocationPayload.builder()
                .bookingId(bookingId)
                .lat(17.4485)
                .lng(78.3748)
                .bearing(0.0)
                .timestamp(t1)
                .build();
        trackingService.processLocationUpdate(bookingId, first, null);

        // Stale update with older timestamp
        LocationPayload stale = LocationPayload.builder()
                .bookingId(bookingId)
                .lat(17.4500)
                .lng(78.3800)
                .bearing(90.0)
                .timestamp(t1 - 1000)
                .build();

        LocationPayload result = trackingService.processLocationUpdate(bookingId, stale, null);

        // Result should retain the original coordinates, not the stale ones
        assertEquals(17.4485, result.getLat());
        assertEquals(78.3748, result.getLng());
    }

    @Test
    void testProcessLocationUpdate_FutureTimestampClamped() {
        String bookingId = "BK-FUTURE";
        long wayInFuture = System.currentTimeMillis() + 100_000L;

        LocationPayload payload = LocationPayload.builder()
                .bookingId(bookingId)
                .lat(17.4485)
                .lng(78.3748)
                .timestamp(wayInFuture)
                .build();

        LocationPayload result = trackingService.processLocationUpdate(bookingId, payload, null);
        assertNotNull(result);
        assertTrue(result.getTimestamp() <= System.currentTimeMillis() + 5000);
    }

    @Test
    void testProcessLocationUpdate_InvalidCoordinates_ThrowsException() {
        String bookingId = "BK-INVALID";

        LocationPayload invalidLat = LocationPayload.builder()
                .bookingId(bookingId)
                .lat(95.0) // Invalid > 90
                .lng(78.3748)
                .build();

        assertThrows(IllegalArgumentException.class, () ->
                trackingService.processLocationUpdate(bookingId, invalidLat, null));

        LocationPayload nanCoord = LocationPayload.builder()
                .bookingId(bookingId)
                .lat(Double.NaN)
                .lng(78.3748)
                .build();

        assertThrows(IllegalArgumentException.class, () ->
                trackingService.processLocationUpdate(bookingId, nanCoord, null));
    }

    @Test
    void testProcessLocationUpdate_SlidingWindowRateLimit_ThrottlesExcessivePings() {
        String bookingId = "BK-RATELIMIT";
        String workerId = "WRK-FAST";

        // maxUpdates is 5 in this test setup; move ~2 meters per ping (exceeds stationary duplicate filter <0.2m, but within <15m rate limit threshold)
        for (int i = 0; i < 5; i++) {
            LocationPayload p = LocationPayload.builder()
                    .bookingId(bookingId)
                    .partnerId(workerId)
                    .lat(17.4485 + (i * 0.00002)) // ~2.2m shift per ping
                    .lng(78.3748)
                    .bearing((double) (i * 10))
                    .timestamp(System.currentTimeMillis() + i * 10)
                    .build();
            trackingService.processLocationUpdate(bookingId, p, workerId);
        }

        // 6th update without sufficient movement (< 15m cumulative) should trigger RateLimitExceededException
        LocationPayload excessive = LocationPayload.builder()
                .bookingId(bookingId)
                .partnerId(workerId)
                .lat(17.4485 + (5 * 0.00002))
                .lng(78.3748)
                .bearing(50.0)
                .timestamp(System.currentTimeMillis() + 100)
                .build();

        assertThrows(RateLimitExceededException.class, () ->
                trackingService.processLocationUpdate(bookingId, excessive, workerId));
    }

    @Test
    void testProcessLocationUpdate_LegitimateMovement_BypassesRateLimit() {
        String bookingId = "BK-MOVEMENT";
        String workerId = "WRK-MOVING";

        // Fill rate limit quota with stationary pings
        for (int i = 0; i < 5; i++) {
            LocationPayload p = LocationPayload.builder()
                    .bookingId(bookingId)
                    .partnerId(workerId)
                    .lat(17.4485)
                    .lng(78.3748)
                    .timestamp(System.currentTimeMillis() + i * 10)
                    .build();
            trackingService.processLocationUpdate(bookingId, p, workerId);
        }

        // 6th update has moved 200+ meters away
        LocationPayload movingPayload = LocationPayload.builder()
                .bookingId(bookingId)
                .partnerId(workerId)
                .lat(17.4510) // ~280 meters north
                .lng(78.3748)
                .timestamp(System.currentTimeMillis() + 100)
                .build();

        // Should NOT throw RateLimitExceededException because vehicle legitimately moved
        assertDoesNotThrow(() ->
                trackingService.processLocationUpdate(bookingId, movingPayload, workerId));
    }

    @Test
    void testProcessLocationUpdate_AssignmentSecurity_UnauthorizedWorker_ThrowsSecurityException() throws Exception {
        String bookingId = "BK-SECURE";
        Map<String, Object> assignment = Map.of(
                "bookingId", bookingId,
                "userId", "AUTHORIZED-USER-UUID",
                "workerId", "AUTHORIZED-WORKER-UUID",
                "status", "ACCEPTED"
        );
        when(valueOperations.get("tracking:assignment:" + bookingId))
                .thenReturn(objectMapper.writeValueAsString(assignment));

        LocationPayload p = LocationPayload.builder()
                .bookingId(bookingId)
                .lat(17.4485)
                .lng(78.3748)
                .build();

        assertThrows(SecurityException.class, () ->
                trackingService.processLocationUpdate(bookingId, p, "MALICIOUS-HACKER-UUID"));
    }

    @Test
    void testProcessLocationUpdate_CompletedBooking_ThrowsIllegalStateException() throws Exception {
        String bookingId = "BK-DONE";
        Map<String, Object> assignment = Map.of(
                "bookingId", bookingId,
                "status", "COMPLETED"
        );
        when(valueOperations.get("tracking:assignment:" + bookingId))
                .thenReturn(objectMapper.writeValueAsString(assignment));

        LocationPayload p = LocationPayload.builder()
                .bookingId(bookingId)
                .lat(17.4485)
                .lng(78.3748)
                .build();

        assertThrows(IllegalStateException.class, () ->
                trackingService.processLocationUpdate(bookingId, p, null));
    }

    @Test
    void testCalculateDistanceAndEta() {
        String bookingId = "BK-ETA";
        LocationPayload p = LocationPayload.builder()
                .bookingId(bookingId)
                .lat(17.4485)
                .lng(78.3748)
                .timestamp(System.currentTimeMillis())
                .build();
        trackingService.processLocationUpdate(bookingId, p, null);

        // Customer ~2.5 km away
        Map<String, Object> result = trackingService.calculateDistanceAndEta(bookingId, 17.4700, 78.3748);

        assertTrue((Boolean) result.get("found"));
        assertEquals(bookingId, result.get("bookingId"));
        assertNotNull(result.get("distanceKm"));
        assertNotNull(result.get("etaMinutes"));
        assertTrue(((Number) result.get("etaMinutes")).intValue() > 0);
    }

    // =========================================================================
    // New tests added for GPS accuracy validation, sequence numbers, and
    // the corrected stationary noise suppression threshold (1.5m).
    // =========================================================================

    @Test
    void testProcessLocationUpdate_PoorAccuracy_Rejected() {
        String bookingId = "BK-ACC-BAD";
        LocationPayload payload = LocationPayload.builder()
                .bookingId(bookingId)
                .lat(17.4485)
                .lng(78.3748)
                .accuracy(100.0)  // Exceeds maxAccuracyMeters=50m
                .timestamp(System.currentTimeMillis())
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                trackingService.processLocationUpdate(bookingId, payload, null));
        assertTrue(ex.getMessage().contains("accuracy"), "Exception should mention accuracy");
    }

    @Test
    void testProcessLocationUpdate_BorderlineAccuracy_Accepted() {
        // Exactly at the threshold (50.0m) should pass
        String bookingId = "BK-ACC-OK";
        LocationPayload payload = LocationPayload.builder()
                .bookingId(bookingId)
                .lat(17.4485)
                .lng(78.3748)
                .accuracy(50.0)  // Equal to maxAccuracyMeters, not exceeding
                .timestamp(System.currentTimeMillis())
                .build();

        LocationPayload result = assertDoesNotThrow(() ->
                trackingService.processLocationUpdate(bookingId, payload, null));
        assertNotNull(result);
        assertEquals(17.4485, result.getLat());
    }

    @Test
    void testProcessLocationUpdate_SequenceNumber_Monotonic() {
        String bookingId = "BK-SEQ";
        long baseTs = System.currentTimeMillis();

        LocationPayload p1 = LocationPayload.builder()
                .bookingId(bookingId).lat(17.4485).lng(78.3748).timestamp(baseTs).build();
        LocationPayload r1 = trackingService.processLocationUpdate(bookingId, p1, null);

        LocationPayload p2 = LocationPayload.builder()
                .bookingId(bookingId).lat(17.4510).lng(78.3748).timestamp(baseTs + 5000).build();
        LocationPayload r2 = trackingService.processLocationUpdate(bookingId, p2, null);

        assertNotNull(r1.getSequenceNumber(), "sequenceNumber must not be null");
        assertNotNull(r2.getSequenceNumber(), "sequenceNumber must not be null");
        assertTrue(r2.getSequenceNumber() > r1.getSequenceNumber(),
                "sequenceNumber must increase monotonically: " + r1.getSequenceNumber() + " vs " + r2.getSequenceNumber());
    }

    @Test
    void testProcessLocationUpdate_SequenceNumber_IsolatedPerBooking() {
        String booking1 = "BK-ISOLATED-A";
        String booking2 = "BK-ISOLATED-B";
        long ts = System.currentTimeMillis();

        LocationPayload a = LocationPayload.builder().bookingId(booking1).lat(17.4485).lng(78.3748).timestamp(ts).build();
        LocationPayload b = LocationPayload.builder().bookingId(booking2).lat(17.4485).lng(78.3748).timestamp(ts).build();

        LocationPayload rA = trackingService.processLocationUpdate(booking1, a, null);
        LocationPayload rB = trackingService.processLocationUpdate(booking2, b, null);

        // Both should start at seq=1 independently
        assertEquals(1L, rA.getSequenceNumber(), "First update for booking1 should have seq=1");
        assertEquals(1L, rB.getSequenceNumber(), "First update for booking2 should have seq=1 independently");
    }

    @Test
    void testProcessLocationUpdate_StationaryNoise_BelowNewThreshold_Suppressed() {
        // Movement of ~1.4m is below the new 1.5m noise threshold and should be suppressed
        String bookingId = "BK-NOISE-BELOW";
        long ts = System.currentTimeMillis();

        LocationPayload first = LocationPayload.builder()
                .bookingId(bookingId).lat(17.44850).lng(78.37480).bearing(45.0).timestamp(ts).build();
        trackingService.processLocationUpdate(bookingId, first, null);

        // ~1.1m north (0.00001 deg lat ~ 1.11m), same bearing, within 3s
        LocationPayload noise = LocationPayload.builder()
                .bookingId(bookingId).lat(17.44851).lng(78.37480).bearing(45.0).timestamp(ts + 500).build();
        LocationPayload result = trackingService.processLocationUpdate(bookingId, noise, null);

        // Should return the original coordinates (suppressed, not broadcast)
        assertEquals(17.44850, result.getLat(), 0.00001);
    }

    @Test
    void testProcessLocationUpdate_Movement_AboveNewThreshold_Passes() {
        // Movement of ~3.3m (0.00003 deg lat ~ 3.3m) is above 1.5m noise threshold and should broadcast
        String bookingId = "BK-NOISE-ABOVE";
        long ts = System.currentTimeMillis();

        LocationPayload first = LocationPayload.builder()
                .bookingId(bookingId).lat(17.44850).lng(78.37480).bearing(45.0).timestamp(ts).build();
        trackingService.processLocationUpdate(bookingId, first, null);

        // ~3.3m north, same bearing, within 3s
        LocationPayload moved = LocationPayload.builder()
                .bookingId(bookingId).lat(17.44853).lng(78.37480).bearing(45.0).timestamp(ts + 500).build();
        LocationPayload result = trackingService.processLocationUpdate(bookingId, moved, null);

        // Should have the new coordinates (broadcast happened)
        assertEquals(17.44853, result.getLat(), 0.00001,
                "Update above noise threshold must broadcast new coordinates");
        assertNotNull(result.getSequenceNumber(), "Broadcast update must carry a sequenceNumber");
    }
}

