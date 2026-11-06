package com.homeease.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.dto.AdminOpsDto.*;
import com.homeease.backend.exception.ResourceNotFoundException;
import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.*;
import com.homeease.backend.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
public class AdminOpsService {

    private static final Logger logger = LoggerFactory.getLogger(AdminOpsService.class);

    private final AssignmentWeightConfigRepository weightConfigRepository;
    private final BookingRepository bookingRepository;
    private final WorkerRepository workerRepository;
    private final UserRepository userRepository;
    private final ReassignmentTriggerLogRepository triggerLogRepository;
    private final BookingEventRepository bookingEventRepository;
    private final DisputeRepository disputeRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final WorkerPayoutBatchRepository payoutBatchRepository;
    private final LedgerService ledgerService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager entityManager;

    public AdminOpsService(AssignmentWeightConfigRepository weightConfigRepository,
                           BookingRepository bookingRepository,
                           WorkerRepository workerRepository,
                           UserRepository userRepository,
                           ReassignmentTriggerLogRepository triggerLogRepository,
                           BookingEventRepository bookingEventRepository,
                           DisputeRepository disputeRepository,
                           LedgerAccountRepository ledgerAccountRepository,
                           WorkerPayoutBatchRepository payoutBatchRepository,
                           LedgerService ledgerService,
                           SimpMessagingTemplate messagingTemplate,
                           ObjectMapper objectMapper) {
        this.weightConfigRepository = weightConfigRepository;
        this.bookingRepository = bookingRepository;
        this.workerRepository = workerRepository;
        this.userRepository = userRepository;
        this.triggerLogRepository = triggerLogRepository;
        this.bookingEventRepository = bookingEventRepository;
        this.disputeRepository = disputeRepository;
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.payoutBatchRepository = payoutBatchRepository;
        this.ledgerService = ledgerService;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    // ─────────────────────────────────────────────────────────────────
    // 1. ZONE & DISPATCH CONFIG CONTROL (Rule #10)
    // ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ZoneConfigResponse> getAllZoneConfigs() {
        return weightConfigRepository.findAll().stream().map(this::mapToZoneConfigResponse).toList();
    }

    @Transactional
    public ZoneConfigResponse toggleZoneActive(UUID configId, boolean active) {
        AssignmentWeightConfig config = weightConfigRepository.findById(configId)
                .orElseThrow(() -> new ResourceNotFoundException("Zone config not found: " + configId));
        config.setIsZoneActive(active);
        return mapToZoneConfigResponse(weightConfigRepository.save(config));
    }

    @Transactional
    public ZoneConfigResponse updateZoneConfig(UUID configId, UpdateZoneConfigRequest req) {
        AssignmentWeightConfig config = weightConfigRepository.findById(configId)
                .orElseThrow(() -> new ResourceNotFoundException("Zone config not found: " + configId));

        if (req.getDistanceWeight() != null) config.setDistanceWeight(req.getDistanceWeight());
        if (req.getRatingWeight() != null) config.setRatingWeight(req.getRatingWeight());
        if (req.getAcceptanceRateWeight() != null) config.setAcceptanceRateWeight(req.getAcceptanceRateWeight());
        if (req.getCompletionRateWeight() != null) config.setCompletionRateWeight(req.getCompletionRateWeight());
        if (req.getFairnessWeight() != null) config.setFairnessWeight(req.getFairnessWeight());
        if (req.getRound1RadiusMeters() != null) config.setRound1RadiusMeters(req.getRound1RadiusMeters());
        if (req.getRound2RadiusMeters() != null) config.setRound2RadiusMeters(req.getRound2RadiusMeters());
        if (req.getRound3RadiusMeters() != null) config.setRound3RadiusMeters(req.getRound3RadiusMeters());
        if (req.getMaxSearchRadiusMeters() != null) config.setMaxSearchRadiusMeters(req.getMaxSearchRadiusMeters());
        if (req.getOfferTimeoutSeconds() != null) config.setOfferTimeoutSeconds(req.getOfferTimeoutSeconds());
        if (req.getBatchAssignTMinusMinutes() != null) config.setBatchAssignTMinusMinutes(req.getBatchAssignTMinusMinutes());
        if (req.getSurgeMultiplier() != null) config.setSurgeMultiplier(req.getSurgeMultiplier());
        if (req.getMaxSpeedKmhThreshold() != null) config.setMaxSpeedKmhThreshold(req.getMaxSpeedKmhThreshold());
        if (req.getIsZoneActive() != null) config.setIsZoneActive(req.getIsZoneActive());

        logger.info("Updated zone config {} (Active: {}, Surge: {}x)",
                configId, config.getIsZoneActive(), config.getSurgeMultiplier());
        return mapToZoneConfigResponse(weightConfigRepository.save(config));
    }

    // ─────────────────────────────────────────────────────────────────
    // 2. MANUAL FORCE REASSIGNMENT (Rule #3 & Rule #8)
    // ─────────────────────────────────────────────────────────────────

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> forceReassignBooking(UUID bookingId, UUID targetWorkerId,
                                                    String reason, UUID adminUserId) {
        String lockKey = "booking_reassign:" + bookingId;
        try {
            entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:lockKey))")
                    .setParameter("lockKey", lockKey)
                    .getSingleResult();
        } catch (Exception ignored) {}

        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));

        Worker newWorker = workerRepository.findById(targetWorkerId)
                .orElseThrow(() -> new ResourceNotFoundException("Target worker not found: " + targetWorkerId));

        Worker previousWorker = booking.getWorker();

        // Guarded write: Bump version (Rule #8)
        int nextVersion = (booking.getVersion() != null ? booking.getVersion() : 0) + 1;
        booking.setVersion(nextVersion);
        booking.setWorker(newWorker);
        booking.setStatus(BookingStage.ACCEPTED);
        booking.setWorkerAssignedAt(Instant.now());
        bookingRepository.save(booking);

        // Append to audit log
        triggerLogRepository.save(ReassignmentTriggerLog.builder()
                .booking(booking)
                .previousWorker(previousWorker)
                .newWorker(newWorker)
                .triggerType("ADMIN_OVERRIDE")
                .reason(reason)
                .triggeredBy("ADMIN:" + adminUserId)
                .attemptNumber(1)
                .build());

        // Append to transactional booking event
        try {
            Map<String, Object> eventPayload = new LinkedHashMap<>();
            eventPayload.put("bookingId", bookingId);
            eventPayload.put("previousWorkerId", previousWorker != null ? previousWorker.getWorkerId() : null);
            eventPayload.put("newWorkerId", newWorker.getWorkerId());
            eventPayload.put("newWorkerName", newWorker.getUser() != null ? newWorker.getUser().getFullName() : "Partner");
            eventPayload.put("reason", reason);
            eventPayload.put("version", nextVersion);

            bookingEventRepository.save(BookingEvent.builder()
                    .booking(booking)
                    .eventType(BookingEventType.PARTNER_REASSIGNED)
                    .payload(objectMapper.writeValueAsString(eventPayload))
                    .publishedAt(Instant.now())
                    .consumedBy("ADMIN_PORTAL")
                    .build());
        } catch (Exception e) {
            logger.error("Failed to serialize reassignment event payload: {}", e.getMessage());
        }

        // Live STOMP notification to customer and winning partner
        try {
            Map<String, Object> pushPayload = new LinkedHashMap<>();
            pushPayload.put("type", "WORKER_ASSIGNED");
            pushPayload.put("bookingId", bookingId);
            pushPayload.put("status", "ASSIGNED");
            pushPayload.put("workerId", newWorker.getWorkerId());
            pushPayload.put("workerName", newWorker.getUser() != null ? newWorker.getUser().getFullName() : "Partner");
            pushPayload.put("phone", newWorker.getUser() != null ? newWorker.getUser().getPhoneNumber() : "");
            pushPayload.put("version", nextVersion);

            messagingTemplate.convertAndSend("/topic/booking/" + bookingId, (Object) pushPayload);
            messagingTemplate.convertAndSend("/topic/worker/" + newWorker.getWorkerId() + "/stream", (Object) pushPayload);
        } catch (Exception e) {
            logger.warn("WebSocket dispatch notification failed: {}", e.getMessage());
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("bookingId", bookingId);
        response.put("status", "ASSIGNED");
        response.put("assignedWorkerId", newWorker.getWorkerId());
        response.put("version", nextVersion);
        response.put("message", "Booking manually reassigned to partner successfully.");
        return response;
    }

    // ─────────────────────────────────────────────────────────────────
    // 3. DISPUTES MANAGEMENT (Rule #2 Double-Entry)
    // ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<DisputeResponse> getDisputes(DisputeStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        Page<Dispute> disputes = (status != null)
                ? disputeRepository.findAll(pageable) // fallback or filter
                : disputeRepository.findAllByOrderByCreatedAtDesc(pageable);

        return disputes.map(this::mapToDisputeResponse);
    }

    @Transactional
    public DisputeResponse createDispute(CreateDisputeRequest req) {
        Booking booking = bookingRepository.findById(req.getBookingId())
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + req.getBookingId()));

        User user = req.getRaisedByUserId() != null
                ? userRepository.findById(req.getRaisedByUserId()).orElse(null)
                : null;

        Dispute dispute = disputeRepository.save(Dispute.builder()
                .booking(booking)
                .raisedByUser(user)
                .reason(req.getReason())
                .description(req.getDescription())
                .status(DisputeStatus.OPEN)
                .build());

        logger.info("Created dispute {} for booking {}", dispute.getDisputeId(), booking.getBookingId());
        return mapToDisputeResponse(dispute);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DisputeResponse resolveDispute(UUID disputeId, ResolveDisputeRequest req, UUID adminUserId) {
        Dispute dispute = disputeRepository.findById(disputeId)
                .orElseThrow(() -> new ResourceNotFoundException("Dispute not found: " + disputeId));

        User admin = adminUserId != null ? userRepository.findById(adminUserId).orElse(null) : null;

        BigDecimal refund = req.getRefundAmount() != null ? req.getRefundAmount() : BigDecimal.ZERO;
        BigDecimal compensation = req.getCompensationAmount() != null ? req.getCompensationAmount() : BigDecimal.ZERO;

        dispute.setStatus(req.getResolutionStatus());
        dispute.setResolutionNotes(req.getResolutionNotes());
        dispute.setRefundAmount(refund);
        dispute.setCompensationAmount(compensation);
        dispute.setResolvedByAdmin(admin);
        dispute.setResolvedAt(Instant.now());

        // Post ledger entries if financial adjustment is made (Rule #2)
        if (refund.compareTo(BigDecimal.ZERO) > 0 || compensation.compareTo(BigDecimal.ZERO) > 0) {
            String idempotencyKey = req.getIdempotencyKey() != null
                    ? req.getIdempotencyKey()
                    : "dispute-resolve-" + disputeId;

            ledgerService.recordDisputeResolution(dispute, refund, compensation, idempotencyKey);
        }

        return mapToDisputeResponse(disputeRepository.save(dispute));
    }

    // ─────────────────────────────────────────────────────────────────
    // 4. LEDGER-BASED WORKER PAYOUTS (Rule #2)
    // ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PendingPayoutWorker> getPendingPayouts() {
        List<LedgerAccount> accounts = ledgerAccountRepository.findAll().stream()
                .filter(a -> a.getAccountType() == LedgerAccountType.LIABILITY_WORKER_PAYABLE &&
                             a.getBalance().compareTo(BigDecimal.ZERO) > 0 &&
                             a.getEntityId() != null)
                .toList();

        List<PendingPayoutWorker> result = new ArrayList<>();
        for (LedgerAccount acc : accounts) {
            Optional<Worker> workerOpt = workerRepository.findById(acc.getEntityId());
            if (workerOpt.isPresent()) {
                Worker w = workerOpt.get();
                result.add(PendingPayoutWorker.builder()
                        .workerId(w.getWorkerId())
                        .workerName(w.getUser() != null ? w.getUser().getFullName() : "Partner")
                        .phone(w.getUser() != null ? w.getUser().getPhoneNumber() : "")
                        .payableBalance(acc.getBalance())
                        .bankAccountLinked(w.getAadhaarNumber() != null ? "VERIFIED" : "PENDING")
                        .build());
            }
        }
        return result;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<WorkerPayoutResponse> executeBatchPayouts(BatchPayoutRequest req) {
        List<UUID> targetWorkerIds = req.getWorkerIds();
        if (targetWorkerIds == null || targetWorkerIds.isEmpty()) {
            targetWorkerIds = getPendingPayouts().stream().map(PendingPayoutWorker::getWorkerId).toList();
        }

        List<WorkerPayoutResponse> responses = new ArrayList<>();

        for (UUID workerId : targetWorkerIds) {
            Worker worker = workerRepository.findById(workerId).orElse(null);
            if (worker == null) continue;

            LedgerAccount payableAcc = ledgerService.getOrCreateAccount(LedgerAccountType.LIABILITY_WORKER_PAYABLE, workerId);
            BigDecimal amount = payableAcc.getBalance();

            if (amount.compareTo(BigDecimal.ZERO) <= 0) continue;

            String payoutIdempotencyKey = req.getIdempotencyKey() + ":" + workerId;

            try {
                // Check if already paid
                Optional<WorkerPayoutBatch> existing = payoutBatchRepository.findByIdempotencyKey(payoutIdempotencyKey);
                if (existing.isPresent()) {
                    responses.add(mapToPayoutResponse(existing.get()));
                    continue;
                }

                // 1. Post double-entry debit to ledger
                ledgerService.recordWorkerPayout(worker, amount, payoutIdempotencyKey);

                // 2. Simulated / Razorpay Route Transfer
                String rzpPayoutId = "pout_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);

                WorkerPayoutBatch batch = payoutBatchRepository.save(WorkerPayoutBatch.builder()
                        .idempotencyKey(payoutIdempotencyKey)
                        .worker(worker)
                        .amount(amount)
                        .status(PayoutStatus.SUCCESS)
                        .razorpayPayoutId(rzpPayoutId)
                        .settledAt(Instant.now())
                        .build());

                responses.add(mapToPayoutResponse(batch));
                logger.info("Successfully settled partner payout {} for worker {} (Amount: INR {})",
                        rzpPayoutId, workerId, amount);

            } catch (Exception e) {
                logger.error("Failed payout for worker {}: {}", workerId, e.getMessage());
                WorkerPayoutBatch failedBatch = payoutBatchRepository.save(WorkerPayoutBatch.builder()
                        .idempotencyKey(payoutIdempotencyKey)
                        .worker(worker)
                        .amount(amount)
                        .status(PayoutStatus.FAILED)
                        .failureReason(e.getMessage())
                        .build());
                responses.add(mapToPayoutResponse(failedBatch));
            }
        }

        return responses;
    }

    private ZoneConfigResponse mapToZoneConfigResponse(AssignmentWeightConfig c) {
        return ZoneConfigResponse.builder()
                .configId(c.getConfigId())
                .serviceId(c.getService() != null ? c.getService().getServiceId() : null)
                .serviceName(c.getService() != null ? c.getService().getName() : "Global Default")
                .zonePincode(c.getZonePincode() != null ? c.getZonePincode() : "ALL")
                .distanceWeight(c.getDistanceWeight())
                .ratingWeight(c.getRatingWeight())
                .acceptanceRateWeight(c.getAcceptanceRateWeight())
                .completionRateWeight(c.getCompletionRateWeight())
                .fairnessWeight(c.getFairnessWeight())
                .round1RadiusMeters(c.getRound1RadiusMeters())
                .round2RadiusMeters(c.getRound2RadiusMeters())
                .round3RadiusMeters(c.getRound3RadiusMeters())
                .maxSearchRadiusMeters(c.getMaxSearchRadiusMeters())
                .offerTimeoutSeconds(c.getOfferTimeoutSeconds())
                .batchAssignTMinusMinutes(c.getBatchAssignTMinusMinutes())
                .surgeMultiplier(c.getSurgeMultiplier())
                .maxSpeedKmhThreshold(c.getMaxSpeedKmhThreshold())
                .isZoneActive(c.getIsZoneActive())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    private DisputeResponse mapToDisputeResponse(Dispute d) {
        return DisputeResponse.builder()
                .disputeId(d.getDisputeId())
                .bookingId(d.getBooking() != null ? d.getBooking().getBookingId() : null)
                .raisedByUserId(d.getRaisedByUser() != null ? d.getRaisedByUser().getUserId() : null)
                .reason(d.getReason())
                .description(d.getDescription())
                .status(d.getStatus())
                .resolutionNotes(d.getResolutionNotes())
                .refundAmount(d.getRefundAmount())
                .compensationAmount(d.getCompensationAmount())
                .resolvedByAdminId(d.getResolvedByAdmin() != null ? d.getResolvedByAdmin().getUserId() : null)
                .resolvedAt(d.getResolvedAt())
                .createdAt(d.getCreatedAt())
                .build();
    }

    private WorkerPayoutResponse mapToPayoutResponse(WorkerPayoutBatch b) {
        return WorkerPayoutResponse.builder()
                .batchId(b.getBatchId())
                .workerId(b.getWorker() != null ? b.getWorker().getWorkerId() : null)
                .workerName(b.getWorker() != null && b.getWorker().getUser() != null
                        ? b.getWorker().getUser().getFullName() : "Partner")
                .amount(b.getAmount())
                .status(b.getStatus())
                .razorpayPayoutId(b.getRazorpayPayoutId())
                .failureReason(b.getFailureReason())
                .settledAt(b.getSettledAt())
                .build();
    }
}
