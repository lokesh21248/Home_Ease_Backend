package com.homeease.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.homeease.backend.model.entity.Worker;
import com.homeease.backend.repository.*;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * Verifies WHEN the services touch worker presence: explicit online status, fresh-fix requirement,
 * offline cleanup, and the dispatch gate. Redis I/O itself is covered by WorkerPresenceServiceTest.
 */
class WorkerPresenceWiringTest {

    private WorkerRepository workerRepository;
    private WorkerPresenceService presence;
    private WorkerService workerService;
    private final UUID userId = UUID.randomUUID();
    private final UUID workerId = UUID.randomUUID();
    private Worker worker;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        workerRepository = mock(WorkerRepository.class);
        presence = mock(WorkerPresenceService.class);
        when(presence.isEnforced()).thenReturn(true);
        when(presence.heartbeatTtl()).thenReturn(java.time.Duration.ofSeconds(60));
        workerService = new WorkerService(workerRepository, mock(UserRepository.class), mock(SubServiceRepository.class),
                mock(WorkerServiceLinkRepository.class), mock(PaymentRepository.class), mock(ReviewRepository.class),
                mock(RedisTemplate.class), presence);
        worker = Worker.builder().workerId(workerId).isVerified(true).isOnline(false)
                .currentLat(10.0).currentLng(20.0).lastLocationUpdatedAt(Instant.now().minusSeconds(86_400)).build();
        when(workerRepository.findByUser_UserId(userId)).thenReturn(Optional.of(worker));
        when(workerRepository.save(any(Worker.class))).thenAnswer(i -> i.getArgument(0));
    }

    // ------------------------------------------------------------------ go online / offline

    @Test
    void going_online_with_a_fresh_fix_registers_presence_at_that_fix() {
        workerService.toggleOnlineStatus(userId, true, 12.97, 77.59);
        verify(presence).registerPresence(eq(workerId), eq(12.97), eq(77.59), eq(null), anyString());
        assertThat(worker.getCurrentLat()).isEqualTo(12.97);
        assertThat(worker.getLastLocationUpdatedAt()).isAfter(Instant.now().minusSeconds(5));
    }

    @Test
    void going_online_without_a_fresh_fix_does_not_resurrect_the_old_location() {
        Instant stale = worker.getLastLocationUpdatedAt();
        workerService.toggleOnlineStatus(userId, true, null, null);
        verify(presence, never()).registerPresence(any(), anyDouble(), anyDouble(), any(), any());
        assertThat(worker.getIsOnline()).isTrue();                    // explicit status is recorded...
        assertThat(worker.getLastLocationUpdatedAt()).isEqualTo(stale); // ...but the stale GPS is NOT re-stamped as fresh
        assertThat(worker.getCurrentLat()).isEqualTo(10.0);
    }

    @Test
    void going_online_with_an_invalid_fix_is_treated_as_no_fix() {
        workerService.toggleOnlineStatus(userId, true, 0.0, 0.0);
        workerService.toggleOnlineStatus(userId, true, 95.0, 20.0);
        workerService.toggleOnlineStatus(userId, true, Double.NaN, 20.0);
        verify(presence, never()).registerPresence(any(), anyDouble(), anyDouble(), any(), any());
    }

    @Test
    void going_offline_clears_presence_even_if_redis_cleanup_fails() {
        worker.setIsOnline(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("redis down")).when(presence).markOffline(workerId);
        workerService.toggleOnlineStatus(userId, false, null, null);
        verify(presence).markOffline(workerId);
        assertThat(worker.getIsOnline()).as("DB flag still flips so dispatch is blocked").isFalse();
    }

    @Test
    void legacy_switch_off_restores_stored_fix_behaviour() {
        when(presence.isEnforced()).thenReturn(false);
        workerService.toggleOnlineStatus(userId, true, null, null);
        verify(presence).registerPresence(eq(workerId), eq(10.0), eq(20.0), eq(null), anyString());
    }

    // ------------------------------------------------------------------ location updates

    @Test
    void location_update_refreshes_presence_only_for_an_explicitly_online_worker() {
        worker.setIsOnline(true);
        workerService.updateLiveLocation(userId, 12.5, 77.5);
        verify(presence).registerPresence(eq(workerId), eq(12.5), eq(77.5), eq(null), anyString());
    }

    @Test
    void location_update_from_an_offline_worker_never_makes_it_discoverable() {
        worker.setIsOnline(false);
        workerService.updateLiveLocation(userId, 12.5, 77.5);
        verify(presence, never()).registerPresence(any(), anyDouble(), anyDouble(), any(), any());
        verify(presence, never()).refreshHeartbeat(any(), any());
    }

    // ------------------------------------------------------------------ worker stream ping

    private FraudDetectionService fraudMock;

    private WorkerStreamService stream() {
        FraudDetectionService fraud = mock(FraudDetectionService.class);
        fraudMock = fraud;
        FraudDetectionService.TelemetryValidationResult ok = mock(FraudDetectionService.TelemetryValidationResult.class);
        when(ok.isValid()).thenReturn(true);
        when(fraud.validateWorkerLocation(any(), anyDouble(), anyDouble(), anyDouble(), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenReturn(ok);
        when(workerRepository.findById(workerId)).thenReturn(Optional.of(worker));
        return new WorkerStreamService(mock(RedisTemplate.class), workerRepository,
                mock(org.springframework.messaging.simp.SimpMessagingTemplate.class), fraud, presence);
    }

    @Test
    void a_ping_from_an_online_worker_registers_presence_and_updates_location_without_toggling_status() {
        worker.setIsOnline(true);
        boolean registered = stream().recordWorkerPing(workerId, 12.9, 77.6, "560001", 8.0);
        assertThat(registered).isTrue();
        verify(presence).registerPresence(eq(workerId), eq(12.9), eq(77.6), eq("560001"), eq("560001"));
        assertThat(worker.getCurrentLat()).isEqualTo(12.9);
        assertThat(worker.getIsOnline()).isTrue();
    }

    @Test
    void a_late_ping_after_going_offline_is_ignored_and_does_not_flip_the_worker_back_online() {
        worker.setIsOnline(false);
        boolean registered = stream().recordWorkerPing(workerId, 12.9, 77.6, null, 8.0);
        assertThat(registered).isFalse();
        verify(presence, never()).registerPresence(any(), anyDouble(), anyDouble(), any(), any());
        assertThat(worker.getIsOnline()).isFalse();
        assertThat(worker.getCurrentLat()).as("offline worker's GPS is not stored either").isEqualTo(10.0);
    }

    @Test
    void pings_from_unverified_blocked_or_unknown_workers_are_ignored() {
        worker.setIsOnline(true);
        worker.setIsVerified(false);
        assertThat(stream().recordWorkerPing(workerId, 12.9, 77.6, null, 8.0)).isFalse();

        worker.setIsVerified(true);
        worker.setBlockedUntil(Instant.now().plusSeconds(600));
        assertThat(stream().recordWorkerPing(workerId, 12.9, 77.6, null, 8.0)).isFalse();

        assertThat(stream().recordWorkerPing(UUID.randomUUID(), 12.9, 77.6, null, 8.0)).isFalse();
        verify(presence, never()).registerPresence(any(), anyDouble(), anyDouble(), any(), any());
    }

    @Test
    void a_ping_with_an_unusable_fix_is_ignored() {
        worker.setIsOnline(true);
        assertThat(stream().recordWorkerPing(workerId, 0.0, 0.0, null, 8.0)).isFalse();
        assertThat(stream().recordWorkerPing(workerId, Double.NaN, 77.0, null, 8.0)).isFalse();
        verify(presence, never()).registerPresence(any(), anyDouble(), anyDouble(), any(), any());
    }

    @Test
    void a_stationary_ping_takes_the_one_command_path_and_skips_fraud_geo_and_full_registration() {
        worker.setIsOnline(true);
        when(presence.tryLightweightRefresh(eq(workerId), eq(12.9), eq(77.6), eq("560001"))).thenReturn(true);

        boolean registered = stream().recordWorkerPing(workerId, 12.9, 77.6, "560001", 8.0);

        assertThat(registered).isTrue();
        verify(presence, never()).registerPresence(any(), anyDouble(), anyDouble(), any(), any());
        org.mockito.Mockito.verifyNoInteractions(fraudMock);
        assertThat(worker.getCurrentLat()).as("Postgres location still updated every ping").isEqualTo(12.9);
        verify(workerRepository).save(worker);
    }

    @Test
    void a_moving_worker_still_gets_the_full_path_with_the_fraud_check() {
        worker.setIsOnline(true);
        when(presence.tryLightweightRefresh(any(), anyDouble(), anyDouble(), any())).thenReturn(false);

        assertThat(stream().recordWorkerPing(workerId, 12.9, 77.6, null, 8.0)).isTrue();

        verify(fraudMock).validateWorkerLocation(any(), anyDouble(), anyDouble(), anyDouble(),
                org.mockito.ArgumentMatchers.anyLong(), any());
        verify(presence).registerPresence(eq(workerId), eq(12.9), eq(77.6), eq(null), eq("ONLINE"));
    }

    @Test
    void a_redis_error_in_the_lightweight_path_falls_back_to_the_full_path() {
        worker.setIsOnline(true);
        when(presence.tryLightweightRefresh(any(), anyDouble(), anyDouble(), any()))
                .thenThrow(new IllegalStateException("redis down"));

        assertThat(stream().recordWorkerPing(workerId, 12.9, 77.6, null, 8.0)).isTrue();
        verify(presence).registerPresence(eq(workerId), eq(12.9), eq(77.6), eq(null), eq("ONLINE"));
    }

    @Test
    void an_offline_worker_never_reaches_the_lightweight_path() {
        worker.setIsOnline(false);
        assertThat(stream().recordWorkerPing(workerId, 12.9, 77.6, null, 8.0)).isFalse();
        verify(presence, never()).tryLightweightRefresh(any(), anyDouble(), anyDouble(), any());
    }

    @Test
    void ping_response_policy_values_come_from_the_single_presence_policy() {
        when(presence.heartbeatTtlSeconds()).thenReturn(60);
        when(presence.heartbeatIntervalSeconds()).thenReturn(15);
        WorkerStreamService s = stream();
        assertThat(s.heartbeatTtlSeconds()).isEqualTo(60);
        assertThat(s.heartbeatIntervalSeconds()).isEqualTo(15);
    }

    // ------------------------------------------------------------------ dispatch gate

    @Test
    void dispatch_eligibility_rejects_a_worker_whose_heartbeat_expired_with_a_specific_reason() {
        BookingAssignmentService assignment = new BookingAssignmentService(workerRepository,
                mock(WorkerServiceLinkRepository.class), mock(BookingRepository.class),
                mock(BookingAssignmentRepository.class), mock(ReviewRepository.class),
                mock(NotificationDeliveryRepository.class), mock(RedisTemplate.class),
                mock(FcmNotificationService.class), mock(BookingDomainEventService.class),
                mock(org.springframework.messaging.simp.SimpMessagingTemplate.class), mock(BookingOtpService.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), mock(DispatchAuditLogRepository.class));
        assignment.setPresenceService(presence);

        worker.setIsOnline(true);
        worker.setLastLocationUpdatedAt(Instant.now());
        when(presence.isDispatchable(worker)).thenReturn(false);

        Map<String, String> reasons = new LinkedHashMap<>();
        assertThat(assignment.isCandidateEligible(worker, null, null, null, reasons)).isFalse();
        assertThat(reasons).containsEntry(workerId.toString(), "HEARTBEAT_EXPIRED");
    }

    @Test
    void dispatch_eligibility_passes_the_presence_gate_when_the_heartbeat_is_alive() {
        BookingAssignmentService assignment = new BookingAssignmentService(workerRepository,
                mock(WorkerServiceLinkRepository.class), mock(BookingRepository.class),
                mock(BookingAssignmentRepository.class), mock(ReviewRepository.class),
                mock(NotificationDeliveryRepository.class), mock(RedisTemplate.class),
                mock(FcmNotificationService.class), mock(BookingDomainEventService.class),
                mock(org.springframework.messaging.simp.SimpMessagingTemplate.class), mock(BookingOtpService.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), mock(DispatchAuditLogRepository.class));
        assignment.setPresenceService(presence);

        worker.setIsOnline(true);
        worker.setLastLocationUpdatedAt(Instant.now());
        when(presence.isDispatchable(worker)).thenReturn(true);

        Map<String, String> reasons = new HashMap<>();
        // no service/pincode constraints supplied, no active bookings (mocked repos return empty)
        assertThat(assignment.isCandidateEligible(worker, null, null, null, reasons)).isTrue();
        assertThat(reasons).doesNotContainKey(workerId.toString());
    }
}
