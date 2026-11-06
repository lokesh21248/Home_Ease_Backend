package com.homeease.backend.service;

import com.homeease.backend.model.entity.AssignmentWeightConfig;
import com.homeease.backend.repository.AssignmentWeightConfigRepository;
import com.homeease.backend.repository.ServiceSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FraudDetectionTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ServiceSessionRepository sessionRepository;

    @Mock
    private AssignmentWeightConfigRepository weightConfigRepository;

    private FraudDetectionService fraudDetectionService;
    private UUID workerId;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        fraudDetectionService = new FraudDetectionService(redisTemplate, sessionRepository, weightConfigRepository);
        workerId = UUID.randomUUID();

        AssignmentWeightConfig config = AssignmentWeightConfig.builder()
                .maxSpeedKmhThreshold(150.0)
                .build();
        lenient().when(weightConfigRepository.findBestMatchingConfig(any(), any())).thenReturn(Optional.of(config));
    }

    @Test
    @DisplayName("Normal travel velocity (30 km/h) is accepted without fraud alert")
    void testNormalTravelAccepted() {
        long now = System.currentTimeMillis();
        // Previous location: Sector 55 (28.4200, 77.1000) 60 seconds ago
        when(valueOperations.get("worker:last_geo:" + workerId))
                .thenReturn("28.4200:77.1000:" + (now - 60000));

        // Current location: 500 meters away (approx 30 km/h)
        FraudDetectionService.TelemetryValidationResult result =
                fraudDetectionService.validateWorkerLocation(workerId, 28.4245, 77.1000, 15.0, now, "122001");

        assertTrue(result.isValid());
        assertEquals("OK", result.getReason());
        assertTrue(result.getCalculatedSpeedKmh() < 60.0);
    }

    @Test
    @DisplayName("Teleportation / impossible speed (> 150 km/h) is flagged and rejected")
    void testImpossibleVelocityRejected() {
        long now = System.currentTimeMillis();
        // Previous location: Gurugram (28.4200, 77.1000) 10 seconds ago
        when(valueOperations.get("worker:last_geo:" + workerId))
                .thenReturn("28.4200:77.1000:" + (now - 10000));

        // Current location: 5 kilometers away in 10 seconds (approx 1800 km/h)
        FraudDetectionService.TelemetryValidationResult result =
                fraudDetectionService.validateWorkerLocation(workerId, 28.4600, 77.1000, 10.0, now, "122001");

        assertFalse(result.isValid());
        assertEquals("IMPOSSIBLE_VELOCITY", result.getReason());
        assertTrue(result.getCalculatedSpeedKmh() > 150.0);
    }

    @Test
    @DisplayName("Inaccurate GPS (> 100 meters accuracy) is rejected")
    void testExcessiveInaccuracyRejected() {
        FraudDetectionService.TelemetryValidationResult result =
                fraudDetectionService.validateWorkerLocation(workerId, 28.4200, 77.1000, 180.0, System.currentTimeMillis(), "122001");

        assertFalse(result.isValid());
        assertEquals("EXCESSIVE_INACCURACY", result.getReason());
    }

    @Test
    @DisplayName("Booking velocity abuse throttles after 5 attempts")
    void testBookingVelocityRateLimiting() {
        UUID customerId = UUID.randomUUID();
        when(valueOperations.increment("rate:booking_velocity:" + customerId))
                .thenReturn(1L)
                .thenReturn(2L)
                .thenReturn(5L)
                .thenReturn(6L);

        assertTrue(fraudDetectionService.checkBookingVelocity(customerId)); // 1
        assertTrue(fraudDetectionService.checkBookingVelocity(customerId)); // 2
        assertTrue(fraudDetectionService.checkBookingVelocity(customerId)); // 5
        assertFalse(fraudDetectionService.checkBookingVelocity(customerId)); // 6 (exceeded)
    }
}
