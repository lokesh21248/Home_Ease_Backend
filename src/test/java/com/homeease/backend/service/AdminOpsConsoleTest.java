package com.homeease.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.dto.AdminOpsDto.*;
import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.*;
import com.homeease.backend.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminOpsConsoleTest {

    @Mock
    private AssignmentWeightConfigRepository weightConfigRepository;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private WorkerRepository workerRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ReassignmentTriggerLogRepository triggerLogRepository;

    @Mock
    private BookingEventRepository bookingEventRepository;

    @Mock
    private DisputeRepository disputeRepository;

    @Mock
    private LedgerAccountRepository ledgerAccountRepository;

    @Mock
    private WorkerPayoutBatchRepository payoutBatchRepository;

    @Mock
    private LedgerService ledgerService;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query nativeQuery;

    private AdminOpsService adminOpsService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        adminOpsService = new AdminOpsService(
                weightConfigRepository,
                bookingRepository,
                workerRepository,
                userRepository,
                triggerLogRepository,
                bookingEventRepository,
                disputeRepository,
                ledgerAccountRepository,
                payoutBatchRepository,
                ledgerService,
                messagingTemplate,
                objectMapper
        );
        ReflectionTestUtils.setField(adminOpsService, "entityManager", entityManager);

        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        lenient().when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        lenient().when(nativeQuery.getSingleResult()).thenReturn(1);
    }

    @Test
    @DisplayName("Zone dispatch config updates dynamic radii, weights, and surge multiplier in DB (Rule #10)")
    void testZoneConfigUpdate() {
        UUID configId = UUID.randomUUID();
        AssignmentWeightConfig config = AssignmentWeightConfig.builder()
                .configId(configId)
                .distanceWeight(0.35)
                .surgeMultiplier(1.0)
                .isZoneActive(true)
                .build();

        when(weightConfigRepository.findById(configId)).thenReturn(Optional.of(config));
        when(weightConfigRepository.save(any(AssignmentWeightConfig.class))).thenAnswer(i -> i.getArgument(0));

        UpdateZoneConfigRequest req = UpdateZoneConfigRequest.builder()
                .distanceWeight(0.40)
                .surgeMultiplier(1.5)
                .round1RadiusMeters(4000.0)
                .build();

        ZoneConfigResponse response = adminOpsService.updateZoneConfig(configId, req);

        assertNotNull(response);
        assertEquals(0.40, response.getDistanceWeight());
        assertEquals(1.5, response.getSurgeMultiplier());
        assertEquals(4000.0, response.getRound1RadiusMeters());
        verify(weightConfigRepository).save(config);
    }

    @Test
    @DisplayName("Manual force reassignment increments booking.version and appends audit event (Rule #8)")
    void testForceReassignmentGuardedWrite() {
        UUID bookingId = UUID.randomUUID();
        UUID targetWorkerId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();

        Booking booking = Booking.builder()
                .bookingId(bookingId)
                .version(1)
                .status(BookingStage.SEARCHING)
                .build();

        Worker targetWorker = Worker.builder()
                .workerId(targetWorkerId)
                .user(User.builder().fullName("Partner Express").phoneNumber("+919876543210").build())
                .build();

        when(bookingRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(booking));
        when(workerRepository.findById(targetWorkerId)).thenReturn(Optional.of(targetWorker));

        Map<String, Object> result = adminOpsService.forceReassignBooking(
                bookingId, targetWorkerId, "Customer VIP priority manual override", adminId);

        assertEquals("ASSIGNED", result.get("status"));
        assertEquals(2, result.get("version"), "Guarded write must bump booking.version");
        assertEquals(targetWorkerId, result.get("assignedWorkerId"));

        verify(bookingRepository).save(booking);
        verify(triggerLogRepository).save(any(ReassignmentTriggerLog.class));
        verify(bookingEventRepository).save(any(BookingEvent.class));
        verify(messagingTemplate, atLeastOnce()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("Dispute resolution with customer refund triggers double-entry ledger entry (Rule #2)")
    void testDisputeResolutionWithLedger() {
        UUID disputeId = UUID.randomUUID();
        Booking booking = Booking.builder().bookingId(UUID.randomUUID()).build();

        Dispute dispute = Dispute.builder()
                .disputeId(disputeId)
                .booking(booking)
                .status(DisputeStatus.OPEN)
                .build();

        when(disputeRepository.findById(disputeId)).thenReturn(Optional.of(dispute));
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(i -> i.getArgument(0));

        ResolveDisputeRequest req = ResolveDisputeRequest.builder()
                .resolutionStatus(DisputeStatus.RESOLVED_REFUNDED)
                .resolutionNotes("Full refund granted due to partner cancellation")
                .refundAmount(BigDecimal.valueOf(499.00))
                .idempotencyKey("dispute-refund-test-1")
                .build();

        DisputeResponse response = adminOpsService.resolveDispute(disputeId, req, UUID.randomUUID());

        assertEquals(DisputeStatus.RESOLVED_REFUNDED, response.getStatus());
        assertEquals(BigDecimal.valueOf(499.00), response.getRefundAmount());

        verify(ledgerService).recordDisputeResolution(
                eq(dispute), eq(BigDecimal.valueOf(499.00)), eq(BigDecimal.ZERO), eq("dispute-refund-test-1"));
    }
}

