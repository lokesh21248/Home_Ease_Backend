package com.homeease.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.homeease.backend.model.entity.Worker;
import com.homeease.backend.repository.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * Pincode and presence eligibility of the production dispatch gate (BookingAssignmentService.isCandidateEligible).
 * These rules had no direct tests; they document that the heartbeat gate was added without disturbing them.
 */
class PincodeEligibilityTest {

    private BookingAssignmentService svc;
    private WorkerPresenceService presence;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        svc = new BookingAssignmentService(mock(WorkerRepository.class), mock(WorkerServiceLinkRepository.class),
                mock(BookingRepository.class), mock(BookingAssignmentRepository.class), mock(ReviewRepository.class),
                mock(NotificationDeliveryRepository.class), mock(RedisTemplate.class),
                mock(FcmNotificationService.class), mock(BookingDomainEventService.class),
                mock(org.springframework.messaging.simp.SimpMessagingTemplate.class), mock(BookingOtpService.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), mock(DispatchAuditLogRepository.class));
        presence = mock(WorkerPresenceService.class);
        svc.setPresenceService(presence);
    }

    private Worker worker(String workingPincodes) {
        return Worker.builder().workerId(UUID.randomUUID()).isOnline(true).isVerified(true)
                .currentLat(12.9716).currentLng(77.5946).lastLocationUpdatedAt(Instant.now())
                .workingPincodes(workingPincodes).build();
    }

    @Test
    void matchesWorkingPincode_handles_lists_separators_and_junk() {
        Worker w = worker("560001, 560002;560003|560004 560005");
        assertThat(svc.matchesWorkingPincode(w, "560003")).isTrue();
        assertThat(svc.matchesWorkingPincode(w, "560 005")).as("non-digits stripped").isTrue();
        assertThat(svc.matchesWorkingPincode(w, "560099")).isFalse();
        assertThat(svc.matchesWorkingPincode(w, "5600")).as("not a 6-digit pincode").isFalse();
        assertThat(svc.matchesWorkingPincode(w, null)).isFalse();
        assertThat(svc.matchesWorkingPincode(null, "560001")).isFalse();
    }

    @Test
    void worker_serving_the_booking_pincode_is_eligible_when_alive() {
        Worker w = worker("560001,560002");
        when(presence.isDispatchable(w)).thenReturn(true);
        Map<String, String> reasons = new LinkedHashMap<>();
        assertThat(svc.isCandidateEligible(w, null, null, "560002", reasons)).isTrue();
        assertThat(reasons).isEmpty();
    }

    @Test
    void worker_not_serving_the_booking_pincode_is_excluded_even_when_alive() {
        Worker w = worker("560001,560002");
        when(presence.isDispatchable(w)).thenReturn(true);
        Map<String, String> reasons = new LinkedHashMap<>();
        assertThat(svc.isCandidateEligible(w, null, null, "110001", reasons)).isFalse();
        assertThat(reasons).containsEntry(w.getWorkerId().toString(), "PINCODE_NOT_SERVICED");
    }

    @Test
    void same_pincode_worker_without_a_heartbeat_is_excluded_with_the_heartbeat_reason() {
        Worker w = worker("560001");
        when(presence.isDispatchable(w)).thenReturn(false);
        Map<String, String> reasons = new LinkedHashMap<>();
        assertThat(svc.isCandidateEligible(w, null, null, "560001", reasons)).isFalse();
        assertThat(reasons).containsEntry(w.getWorkerId().toString(), "HEARTBEAT_EXPIRED");
    }

    @Test
    void offline_worker_is_excluded_before_presence_is_even_consulted() {
        Worker w = worker("560001");
        w.setIsOnline(false);
        Map<String, String> reasons = new LinkedHashMap<>();
        assertThat(svc.isCandidateEligible(w, null, null, "560001", reasons)).isFalse();
        assertThat(reasons).containsEntry(w.getWorkerId().toString(), "WORKER_OFFLINE");
        org.mockito.Mockito.verifyNoInteractions(presence);
    }
}
