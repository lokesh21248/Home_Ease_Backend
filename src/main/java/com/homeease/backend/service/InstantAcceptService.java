package com.homeease.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.exception.JobAlreadyAcceptedException;
import com.homeease.backend.exception.ResourceNotFoundException;
import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.*;
import com.homeease.backend.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.Builder;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class InstantAcceptService {

    private static final Logger logger = LoggerFactory.getLogger(InstantAcceptService.class);

    private final BookingRepository bookingRepository;
    private final WorkerRepository workerRepository;
    private final BookingAssignmentRepository assignmentRepository;
    private final BookingDomainEventService domainEventService;
    private final SimpMessagingTemplate messagingTemplate;
    private final FcmNotificationService fcmNotificationService;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager entityManager;

    public InstantAcceptService(BookingRepository bookingRepository,
                                WorkerRepository workerRepository,
                                BookingAssignmentRepository assignmentRepository,
                                BookingDomainEventService domainEventService,
                                SimpMessagingTemplate messagingTemplate,
                                FcmNotificationService fcmNotificationService,
                                RedisTemplate<String, String> redisTemplate,
                                ObjectMapper objectMapper) {
        this.bookingRepository = bookingRepository;
        this.workerRepository = workerRepository;
        this.assignmentRepository = assignmentRepository;
        this.domainEventService = domainEventService;
        this.messagingTemplate = messagingTemplate;
        this.fcmNotificationService = fcmNotificationService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Getter
    @Builder
    public static class AcceptBookingResult {
        private final boolean success;
        private final UUID bookingId;
        private final UUID workerId;
        private final String workerName;
        private final String pinCode;
        private final int version;
        private final String message;
    }

    /**
     * Atomically claims an instant booking for a worker.
     * Complies with Non-negotiable Rules:
     * - Rule #3: pg_advisory_xact_lock (transaction-level).
     * - Rule #8: Guarded write bumping booking.version and appending booking_event.
     * Guarantees that when multiple workers accept concurrently, exactly one succeeds
     * and all other callers receive JobAlreadyAcceptedException ("job taken").
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AcceptBookingResult acceptBooking(UUID bookingId, UUID workerUserId) {
        String lockKey = "booking_accept:" + bookingId;

        // 1. Transaction-level Postgres advisory lock
        // Serializes concurrent acceptance attempts for this specific booking across all API instances
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:lockKey))")
                .setParameter("lockKey", lockKey)
                .getSingleResult();

        // 2. Pessimistic read with SELECT ... FOR UPDATE
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));

        // 3. Race condition gate: If another worker already won the race, reject immediately
        if (booking.getStatus() != BookingStage.SEARCHING &&
            booking.getStatus() != BookingStage.REQUESTED &&
            booking.getStatus() != BookingStage.PENDING) {
            logger.warn("Worker {} attempted to accept booking {}, but status is already {}. Rejected as JOB_TAKEN.",
                    workerUserId, bookingId, booking.getStatus());
            throw new JobAlreadyAcceptedException("This job has already been accepted by another partner.");
        }

        Worker worker = workerRepository.findByUser_UserId(workerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Worker profile not found for user: " + workerUserId));

        // 4. Generate 4-digit verification PIN
        String pinCode = String.format("%04d", new SecureRandom().nextInt(10000));
        Instant now = Instant.now();

        // 5. Guarded write: Assign winning worker, bump version (Rule #8)
        int currentVersion = booking.getVersion() != null ? booking.getVersion() : 0;
        booking.setWorker(worker);
        booking.setStatus(BookingStage.ACCEPTED);
        booking.setWorkerAssignedAt(now);
        booking.setPinCode(pinCode);
        booking.setVersion(currentVersion + 1);
        booking = bookingRepository.save(booking);

        // 6. Append booking_event (Rule #8)
        try {
            domainEventService.recordEvent(
                    booking,
                    BookingEventType.PARTNER_ACCEPTED,
                    BookingStage.SEARCHING,
                    BookingStage.ACCEPTED,
                    BookingActorType.WORKER,
                    workerUserId
            );
        } catch (Exception e) {
            logger.warn("Domain event recording skipped: {}", e.getMessage());
        }

        // 7. Update winning assignment to ACCEPTED
        assignmentRepository.findByBooking_BookingIdAndWorker_WorkerId(bookingId, worker.getWorkerId())
                .ifPresent(a -> {
                    a.setStatus(AssignmentStatus.ACCEPTED);
                    a.setRespondedAt(now);
                    a.setAcceptedAt(now);
                    assignmentRepository.save(a);
                });

        // 8. Atomically DROP all competing pending assignments for this booking
        assignmentRepository.dropOtherPendingAssignments(bookingId, worker.getWorkerId(), AssignmentStatus.DROPPED, now);

        // 9. Cache active tracking in Redis
        cacheTrackingAssignment(booking, worker, pinCode);

        // 10. Notify customer & broadcast update
        notifyCustomerAndBroadcast(booking, worker, pinCode);

        String workerName = worker.getUser() != null ? worker.getUser().getFullName() : "HomeEase Partner";
        logger.info("Atomic acceptance WON: Worker {} successfully assigned to booking {} (version bumped to {})",
                worker.getWorkerId(), bookingId, booking.getVersion());

        return AcceptBookingResult.builder()
                .success(true)
                .bookingId(booking.getBookingId())
                .workerId(worker.getWorkerId())
                .workerName(workerName)
                .pinCode(pinCode)
                .version(booking.getVersion())
                .message("Job accepted successfully.")
                .build();
    }

    private void cacheTrackingAssignment(Booking booking, Worker worker, String pinCode) {
        try {
            Map<String, Object> assignment = new HashMap<>();
            assignment.put("bookingId", booking.getBookingId().toString());
            assignment.put("workerId", worker.getWorkerId().toString());
            assignment.put("userId", worker.getUser() != null ? worker.getUser().getUserId().toString() : "");
            assignment.put("customerId", booking.getUser() != null ? booking.getUser().getUserId().toString() : "");
            assignment.put("status", "ACCEPTED");
            assignment.put("pinCode", pinCode);
            assignment.put("acceptedAt", Instant.now().toString());
            if (booking.getUserLocation() != null) {
                assignment.put("customerLat", booking.getUserLocation().getY());
                assignment.put("customerLng", booking.getUserLocation().getX());
            }

            String json = objectMapper.writeValueAsString(assignment);
            redisTemplate.opsForValue().set("tracking:assignment:" + booking.getBookingId(), json, Duration.ofHours(24));
        } catch (Exception e) {
            logger.warn("Redis tracking assignment cache error: {}", e.getMessage());
        }
    }

    private void notifyCustomerAndBroadcast(Booking booking, Worker worker, String pinCode) {
        String workerName = worker.getUser() != null ? worker.getUser().getFullName() : "HomeEase Partner";
        UUID customerId = booking.getUser() != null ? booking.getUser().getUserId() : null;

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "WORKER_ASSIGNED");
        payload.put("bookingId", booking.getBookingId().toString());
        payload.put("workerId", worker.getWorkerId().toString());
        payload.put("workerName", workerName);
        payload.put("workerPhone", worker.getUser() != null ? worker.getUser().getPhoneNumber() : "");
        payload.put("status", "ACCEPTED");
        payload.put("pinCode", pinCode);

        try {
            messagingTemplate.convertAndSend("/topic/booking/" + booking.getBookingId(), payload);
            if (customerId != null) {
                messagingTemplate.convertAndSend("/topic/customer/" + customerId + "/booking", payload);
                fcmNotificationService.sendPushNotification(
                        customerId,
                        "Partner Assigned!",
                        workerName + " has accepted your booking."
                );
            }
        } catch (Exception e) {
            logger.debug("Customer assignment notification skipped: {}", e.getMessage());
        }
    }
}

