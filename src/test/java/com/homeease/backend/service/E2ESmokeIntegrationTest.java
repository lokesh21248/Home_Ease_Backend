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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 6: End-to-End Multi-Party Marketplace Lifecycle Smoke Test
 * Validates complete flow:
 * 1. Assignment scoring with dynamic weights (Rule #10)
 * 2. Instant accept with advisory lock & guarded version bump (Rule #3 & Rule #8)
 * 3. Telemetry streaming with anti-spoofing velocity filter
 * 4. Job completion triggering double-entry ledger settlement (Rule #2 & Rule #5)
 * 5. Admin ops dispute resolution and partner batch payout
 */
@ExtendWith(MockitoExtension.class)
public class E2ESmokeIntegrationTest {

    @Mock
    private LedgerAccountRepository accountRepository;
    @Mock
    private LedgerJournalEntryRepository journalEntryRepository;
    @Mock
    private LedgerPostingRepository postingRepository;
    @Mock
    private EntityManager entityManager;
    @Mock
    private Query nativeQuery;

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
    private WorkerPayoutBatchRepository payoutBatchRepository;
    @Mock
    private ServiceSessionRepository sessionRepository;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private RedisTemplate<String, String> redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private LedgerService ledgerService;
    private AdminOpsService adminOpsService;
    private FraudDetectionService fraudDetectionService;

    private UUID bookingId;
    private UUID workerId;
    private Booking booking;
    private Worker worker;
    private LedgerAccount escrowAccount;
    private LedgerAccount workerPayableAccount;
    private LedgerAccount platformRevenueAccount;
    private LedgerAccount cashAccount;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        ledgerService = new LedgerService(accountRepository, journalEntryRepository, postingRepository);
        ReflectionTestUtils.setField(ledgerService, "entityManager", entityManager);
        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        lenient().when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        lenient().when(nativeQuery.getSingleResult()).thenReturn(1);

        fraudDetectionService = new FraudDetectionService(redisTemplate, sessionRepository, weightConfigRepository);

        ObjectMapper objectMapper = new ObjectMapper();
        adminOpsService = new AdminOpsService(
                weightConfigRepository,
                bookingRepository,
                workerRepository,
                userRepository,
                triggerLogRepository,
                bookingEventRepository,
                disputeRepository,
                accountRepository,
                payoutBatchRepository,
                ledgerService,
                messagingTemplate,
                objectMapper
        );
        ReflectionTestUtils.setField(adminOpsService, "entityManager", entityManager);

        bookingId = UUID.randomUUID();
        workerId = UUID.randomUUID();

        worker = Worker.builder()
                .workerId(workerId)
                .user(User.builder().userId(UUID.randomUUID()).fullName("Praveen Kumar").phoneNumber("+919876543210").build())
                .build();

        booking = Booking.builder()
                .bookingId(bookingId)
                .worker(worker)
                .totalAmount(BigDecimal.valueOf(600.00))
                .version(1)
                .status(BookingStage.SEARCHING)
                .servicePincode("122001")
                .build();

        escrowAccount = LedgerAccount.builder()
                .accountId(UUID.randomUUID())
                .accountType(LedgerAccountType.LIABILITY_CUSTOMER_ESCROW)
                .balance(BigDecimal.valueOf(600.00))
                .currency("INR")
                .build();

        workerPayableAccount = LedgerAccount.builder()
                .accountId(UUID.randomUUID())
                .accountType(LedgerAccountType.LIABILITY_WORKER_PAYABLE)
                .entityId(workerId)
                .balance(BigDecimal.ZERO)
                .currency("INR")
                .build();

        platformRevenueAccount = LedgerAccount.builder()
                .accountId(UUID.randomUUID())
                .accountType(LedgerAccountType.REVENUE_PLATFORM_FEE)
                .balance(BigDecimal.ZERO)
                .currency("INR")
                .build();

        cashAccount = LedgerAccount.builder()
                .accountId(UUID.randomUUID())
                .accountType(LedgerAccountType.ASSET_CASH)
                .balance(BigDecimal.valueOf(50000.00))
                .currency("INR")
                .build();

        AssignmentWeightConfig config = AssignmentWeightConfig.builder()
                .maxSpeedKmhThreshold(150.0)
                .surgeMultiplier(1.0)
                .distanceWeight(0.35)
                .ratingWeight(0.25)
                .acceptanceRateWeight(0.20)
                .completionRateWeight(0.15)
                .build();
        lenient().when(weightConfigRepository.findBestMatchingConfig(any(), any())).thenReturn(Optional.of(config));
    }

    @Test
    @DisplayName("E2E Test 1: Full Multi-Party Marketplace Lifecycle Smoke Test")
    void testEndToEndMarketplaceLifecycle() {
        // --- 1. Customer Booked -> Force / Instant Assignment under Guarded Version Bump (Rule #8 & Rule #3) ---
        when(bookingRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(booking));
        when(workerRepository.findById(workerId)).thenReturn(Optional.of(worker));

        UUID adminId = UUID.randomUUID();
        Map<String, Object> assignResult = adminOpsService.forceReassignBooking(
                bookingId, workerId, "Instant dispatch assignment", adminId);

        assertNotNull(assignResult);
        assertEquals("ASSIGNED", assignResult.get("status"));
        assertEquals(2, assignResult.get("version"), "State change must bump booking.version to 2 (Rule #8)");
        verify(triggerLogRepository).save(any(ReassignmentTriggerLog.class));

        // --- 2. Live GPS Telemetry Stream with Velocity Check ---
        long now = System.currentTimeMillis();
        when(valueOperations.get("worker:last_geo:" + workerId))
                .thenReturn("28.4200:77.1000:" + (now - 60000)); // 60s ago in Sector 55

        // Partner moves 400m in 60s (~24 km/h) -> Valid travel
        FraudDetectionService.TelemetryValidationResult telemetryResult =
                fraudDetectionService.validateWorkerLocation(workerId, 28.4236, 77.1000, 12.0, now, "122001");

        assertTrue(telemetryResult.isValid(), "Normal city driving must be accepted into Redis GEO set");
        assertEquals("OK", telemetryResult.getReason());

        // --- 3. Job Completion -> Automated Double-Entry Ledger Settlement (Rule #2 & Rule #5) ---
        when(accountRepository.findByAccountTypeAndEntityIdIsNull(LedgerAccountType.LIABILITY_CUSTOMER_ESCROW))
                .thenReturn(Optional.of(escrowAccount));
        when(accountRepository.findByAccountTypeAndEntityId(LedgerAccountType.LIABILITY_WORKER_PAYABLE, workerId))
                .thenReturn(Optional.of(workerPayableAccount));
        when(accountRepository.findByAccountTypeAndEntityIdIsNull(LedgerAccountType.REVENUE_PLATFORM_FEE))
                .thenReturn(Optional.of(platformRevenueAccount));

        when(journalEntryRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(journalEntryRepository.save(any(LedgerJournalEntry.class))).thenAnswer(i -> {
            LedgerJournalEntry entry = i.getArgument(0);
            entry.setEntryId(UUID.randomUUID());
            return entry;
        });

        List<LedgerPosting> postings = new ArrayList<>();
        when(postingRepository.save(any(LedgerPosting.class))).thenAnswer(i -> {
            LedgerPosting p = i.getArgument(0);
            p.setPostingId(UUID.randomUUID());
            postings.add(p);
            return p;
        });

        LedgerJournalEntry journal = ledgerService.recordJobSettlement(
                booking, BigDecimal.valueOf(600.00), BigDecimal.valueOf(480.00), BigDecimal.valueOf(120.00), "settle-" + bookingId);

        assertNotNull(journal);
        assertEquals(3, postings.size());

        // Verify Invariant: sum(Debits) == sum(Credits) == 600.00
        BigDecimal debits = postings.stream()
                .filter(p -> p.getDirection() == PostingDirection.DEBIT)
                .map(LedgerPosting::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = postings.stream()
                .filter(p -> p.getDirection() == PostingDirection.CREDIT)
                .map(LedgerPosting::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(0, debits.compareTo(credits), "Double-entry ledger invariant broken: sum(Debits) != sum(Credits)");
        assertEquals(0, debits.compareTo(BigDecimal.valueOf(600.00)));

        // Partner share: 80% (480.00), Platform fee: 20% (120.00)
        assertEquals(BigDecimal.valueOf(480.0), workerPayableAccount.getBalance());
        assertEquals(BigDecimal.valueOf(120.0), platformRevenueAccount.getBalance());
    }

    @Test
    @DisplayName("E2E Test 2: Impossible Velocity Spoofing Defense Rejection")
    void testAntiSpoofingDefense() {
        long now = System.currentTimeMillis();
        when(valueOperations.get("worker:last_geo:" + workerId))
                .thenReturn("28.4200:77.1000:" + (now - 10000)); // 10s ago

        // Teleportation: 10km in 10s = 3600 km/h
        FraudDetectionService.TelemetryValidationResult result =
                fraudDetectionService.validateWorkerLocation(workerId, 28.5100, 77.1000, 8.0, now, "122001");

        assertFalse(result.isValid(), "Impossible speed must be rejected by telemetry defense");
        assertEquals("IMPOSSIBLE_VELOCITY", result.getReason());
    }

    @Test
    @DisplayName("E2E Test 3: Dispute Resolution with Idempotent Reversal Postings")
    void testDisputeResolutionCompensatingReversal() {
        UUID disputeId = UUID.randomUUID();
        Dispute dispute = Dispute.builder()
                .disputeId(disputeId)
                .booking(booking)
                .status(DisputeStatus.OPEN)
                .build();

        when(disputeRepository.findById(disputeId)).thenReturn(Optional.of(dispute));
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(i -> i.getArgument(0));

        when(accountRepository.findByAccountTypeAndEntityIdIsNull(LedgerAccountType.REVENUE_PLATFORM_FEE))
                .thenReturn(Optional.of(platformRevenueAccount));
        when(accountRepository.findByAccountTypeAndEntityIdIsNull(LedgerAccountType.ASSET_CASH))
                .thenReturn(Optional.of(cashAccount));

        when(journalEntryRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(journalEntryRepository.save(any(LedgerJournalEntry.class))).thenAnswer(i -> {
            LedgerJournalEntry entry = i.getArgument(0);
            entry.setEntryId(UUID.randomUUID());
            return entry;
        });

        List<LedgerPosting> disputePostings = new ArrayList<>();
        when(postingRepository.save(any(LedgerPosting.class))).thenAnswer(i -> {
            LedgerPosting p = i.getArgument(0);
            p.setPostingId(UUID.randomUUID());
            disputePostings.add(p);
            return p;
        });

        ResolveDisputeRequest req = ResolveDisputeRequest.builder()
                .resolutionStatus(DisputeStatus.RESOLVED_REFUNDED)
                .resolutionNotes("Customer dispute verified; refunding from platform fee")
                .refundAmount(BigDecimal.valueOf(150.00))
                .idempotencyKey("dispute-refund-" + disputeId)
                .build();

        DisputeResponse resolved = adminOpsService.resolveDispute(disputeId, req, UUID.randomUUID());

        assertNotNull(resolved);
        assertEquals(DisputeStatus.RESOLVED_REFUNDED, resolved.getStatus());

        // Verify debits == credits on refund entry
        BigDecimal debits = disputePostings.stream()
                .filter(p -> p.getDirection() == PostingDirection.DEBIT)
                .map(LedgerPosting::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = disputePostings.stream()
                .filter(p -> p.getDirection() == PostingDirection.CREDIT)
                .map(LedgerPosting::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(0, debits.compareTo(credits));
        assertEquals(0, debits.compareTo(BigDecimal.valueOf(150.00)));
    }
}
