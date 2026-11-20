package com.homeease.tracking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.tracking.dto.LocationPayload;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Production-default (strict) GPS ingest: only the booking's assigned worker may publish. */
@ExtendWith(MockitoExtension.class)
class LiveTrackingAssignmentEnforcementTest {

    private static final String BOOKING = UUID.randomUUID().toString();
    private static final String WORKER = UUID.randomUUID().toString();
    private static final String CUSTOMER = UUID.randomUUID().toString();
    private static final String INTRUDER = UUID.randomUUID().toString();

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> ops;
    @Mock private GeoOperations<String, String> geo;
    @Mock private SimpMessagingTemplate broker;

    private LiveTrackingService service;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(ops);
        lenient().when(redis.opsForGeo()).thenReturn(geo);
        service = new LiveTrackingService(redis, new ObjectMapper(), broker);
        ReflectionTestUtils.setField(service, "windowSeconds", 60);
        ReflectionTestUtils.setField(service, "maxUpdates", 30);
        ReflectionTestUtils.setField(service, "movementThresholdMeters", 15.0);
        ReflectionTestUtils.setField(service, "maxAccuracyMeters", 50.0);
        // requireAssignment is intentionally NOT overridden: this is the production default.
        service.init();
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private LocationPayload fix() {
        return LocationPayload.builder().bookingId(BOOKING).lat(17.46).lng(78.51)
                .timestamp(System.currentTimeMillis()).accuracy(8.0).bearing(90.0).build();
    }

    private void givenAssignment(String json) {
        lenient().when(ops.get("tracking:assignment:" + BOOKING)).thenReturn(json);
    }

    @Test
    void strictModeIsTheDefault() {
        assertThat((Boolean) ReflectionTestUtils.getField(service, "requireAssignment")).isTrue();
    }

    @Test
    void updateForABookingWithoutAnAssignmentRecordIsRejectedAndNeverBroadcast() {
        givenAssignment(null);

        assertThatThrownBy(() -> service.processLocationUpdate(BOOKING, fix(), WORKER))
                .isInstanceOf(SecurityException.class).hasMessageContaining("No active assignment");
        assertThatThrownBy(() -> service.processLocationUpdate(BOOKING, fix(), null))
                .isInstanceOf(SecurityException.class);
        verify(broker, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void onlyTheAssignedWorkerMayPublish() {
        givenAssignment("{\"customerId\":\"" + CUSTOMER + "\",\"userId\":\"" + WORKER + "\",\"workerId\":\"" + WORKER
                + "\",\"status\":\"ON_THE_WAY\",\"trackingActive\":true}");

        assertThatThrownBy(() -> service.processLocationUpdate(BOOKING, fix(), INTRUDER))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.processLocationUpdate(BOOKING, fix(), null))
                .isInstanceOf(SecurityException.class).hasMessageContaining("Authentication required");
        assertThatThrownBy(() -> service.processLocationUpdate(BOOKING, fix(), CUSTOMER))
                .as("a customer is not the worker").isInstanceOf(SecurityException.class);
        verify(broker, never()).convertAndSend(anyString(), any(Object.class));

        LocationPayload accepted = service.processLocationUpdate(BOOKING, fix(), WORKER);
        assertThat(accepted.getLat()).isEqualTo(17.46);
        verify(broker).convertAndSend(org.mockito.ArgumentMatchers.eq("/topic/tracking/" + BOOKING), any(Object.class));
    }

    @Test
    void closedBookingsStopAcceptingLocationEvenFromTheAssignedWorker() {
        for (String status : new String[]{"ARRIVED", "IN_PROGRESS", "COMPLETED", "CANCELLED"}) {
            givenAssignment("{\"userId\":\"" + WORKER + "\",\"workerId\":\"" + WORKER + "\",\"status\":\"" + status + "\"}");
            assertThatThrownBy(() -> service.processLocationUpdate(BOOKING, fix(), WORKER))
                    .as("status %s", status).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void staleOrReplayedFixesDoNotOverwriteANewerPosition() {
        givenAssignment("{\"userId\":\"" + WORKER + "\",\"workerId\":\"" + WORKER + "\",\"status\":\"ON_THE_WAY\",\"trackingActive\":true}");
        long now = System.currentTimeMillis();

        LocationPayload newer = LocationPayload.builder().bookingId(BOOKING).lat(17.4700).lng(78.5200)
                .timestamp(now).accuracy(5.0).build();
        service.processLocationUpdate(BOOKING, newer, WORKER);

        LocationPayload older = LocationPayload.builder().bookingId(BOOKING).lat(17.4000).lng(78.4000)
                .timestamp(now - 30_000).accuracy(5.0).build();
        LocationPayload result = service.processLocationUpdate(BOOKING, older, WORKER);

        assertThat(result.getLat()).as("stale event rejected, newer position retained").isEqualTo(17.4700);
        assertThat(service.getLatestLocation(BOOKING).getLat()).isEqualTo(17.4700);
    }
}
