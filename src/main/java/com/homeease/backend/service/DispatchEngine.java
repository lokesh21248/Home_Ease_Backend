package com.homeease.backend.service;

import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class DispatchEngine {

    private static final Logger logger = LoggerFactory.getLogger(DispatchEngine.class);

    private final WorkerRepository workerRepository;
    private final BookingRepository bookingRepository;
    private final AssignmentAttemptRepository assignmentAttemptRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final FcmNotificationService fcmNotificationService;

    @Value("${homeease.dispatch.radius-meters:5000}")
    private double radiusMeters;

    public DispatchEngine(WorkerRepository workerRepository,
                          BookingRepository bookingRepository,
                          AssignmentAttemptRepository assignmentAttemptRepository,
                          RedisTemplate<String, String> redisTemplate,
                          FcmNotificationService fcmNotificationService) {
        this.workerRepository = workerRepository;
        this.bookingRepository = bookingRepository;
        this.assignmentAttemptRepository = assignmentAttemptRepository;
        this.redisTemplate = redisTemplate;
        this.fcmNotificationService = fcmNotificationService;
    }

    @Transactional
    public void triggerSpatialDispatch(Booking booking, UUID targetSubServiceId) {
        logger.info("Initiating 5km spatial candidate matching engine for Booking {}", booking.getBookingId());

        List<Worker> candidates = new ArrayList<>();

        // 1. Attempt Redis GEOSEARCH first
        try {
            Circle area = new Circle(new org.springframework.data.geo.Point(booking.getUserLng(), booking.getUserLat()), new Distance(radiusMeters, Metrics.NEUTRAL));
            GeoResults<RedisGeoCommands.GeoLocation<String>> results = redisTemplate.opsForGeo().radius("active_workers_geo", area);
            if (results != null && !results.getContent().isEmpty()) {
                for (var result : results.getContent()) {
                    UUID workerId = UUID.fromString(result.getContent().getName());
                    workerRepository.findById(workerId).ifPresent(candidates::add);
                }
            }
        } catch (Exception e) {
            logger.warn("Redis GEOSEARCH query fallback to PostGIS native SQL: {}", e.getMessage());
        }

        // 2. PostGIS Fallback Spatial Query
        if (candidates.isEmpty()) {
            candidates = workerRepository.findCandidateWorkersNearby(
                    targetSubServiceId.toString(),
                    booking.getUserLat(),
                    booking.getUserLng(),
                    radiusMeters
            );
        }

        if (candidates.isEmpty()) {
            logger.warn("No available candidate workers found within 5km for booking {}", booking.getBookingId());
            booking.setStatus(BookingStage.WORKER_NOT_FOUND);
            bookingRepository.save(booking);
            return;
        }

        // 3. Dispatch candidate alerts
        for (Worker candidate : candidates) {
            AssignmentAttempt attempt = AssignmentAttempt.builder()
                    .booking(booking)
                    .worker(candidate)
                    .accepted(null)
                    .build();
            assignmentAttemptRepository.save(attempt);

            fcmNotificationService.sendPushNotification(
                    candidate.getUser().getUserId(),
                    "New Nearby Job Request!",
                    "A customer requested " + booking.getService().getName() + " near your location. Accept now!"
            );
        }
    }

    @Transactional
    public Booking handleWorkerAcceptance(UUID bookingId, UUID workerUserId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found"));

        if (booking.getStatus() != BookingStage.SEARCHING) {
            throw new RuntimeException("Booking is no longer searching for workers");
        }

        Worker worker = workerRepository.findByUser_UserId(workerUserId)
                .orElseThrow(() -> new RuntimeException("Worker profile not found"));

        // Generate 4-digit PIN code for service verification
        String pinCode = String.format("%04d", new SecureRandom().nextInt(10000));

        booking.setWorker(worker);
        booking.setStatus(BookingStage.ACCEPTED);
        booking.setPinCode(pinCode);
        bookingRepository.save(booking);

        // Record attempt acceptance
        assignmentAttemptRepository.findByBooking_BookingIdAndWorker_WorkerId(bookingId, worker.getWorkerId())
                .ifPresent(attempt -> {
                    attempt.setAccepted(true);
                    attempt.setRespondedAt(Instant.now());
                    assignmentAttemptRepository.save(attempt);
                });

        fcmNotificationService.sendPushNotification(
                booking.getUser().getUserId(),
                "Worker Assigned!",
                "Worker " + worker.getUser().getFullName() + " accepted your booking request. Your verification PIN is " + pinCode
        );

        return booking;
    }

    @Transactional
    public void handleWorkerRejection(UUID bookingId, UUID workerUserId, UUID targetSubServiceId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found"));

        Worker worker = workerRepository.findByUser_UserId(workerUserId)
                .orElseThrow(() -> new RuntimeException("Worker profile not found"));

        // Apply 1-Hour Disciplinary Lockout Penalty
        worker.setBlockedUntil(Instant.now().plus(1, ChronoUnit.HOURS));
        worker.setIsOnline(false);
        workerRepository.save(worker);

        try {
            redisTemplate.opsForGeo().remove("active_workers_geo", worker.getWorkerId().toString());
        } catch (Exception ignored) {}

        logger.info("Worker {} penalized with 1-hour lockout block for rejecting booking {}", worker.getWorkerId(), bookingId);

        // Record attempt rejection
        assignmentAttemptRepository.findByBooking_BookingIdAndWorker_WorkerId(bookingId, worker.getWorkerId())
                .ifPresent(attempt -> {
                    attempt.setAccepted(false);
                    attempt.setRespondedAt(Instant.now());
                    assignmentAttemptRepository.save(attempt);
                });

        // Re-trigger spatial matching for remaining candidates
        triggerSpatialDispatch(booking, targetSubServiceId);
    }

    @Transactional(readOnly = true)
    public List<com.homeease.backend.dto.WorkerDto.WorkerBookingRequestResponse> getPendingRequestsForWorker(UUID userId) {
        Worker worker = workerRepository.findByUser_UserId(userId).orElse(null);
        if (worker == null) {
            return Collections.emptyList();
        }

        List<AssignmentAttempt> attempts = assignmentAttemptRepository.findByWorker_WorkerIdAndAcceptedIsNull(worker.getWorkerId());
        List<com.homeease.backend.dto.WorkerDto.WorkerBookingRequestResponse> requests = new ArrayList<>();

        for (AssignmentAttempt attempt : attempts) {
            Booking booking = attempt.getBooking();
            if (booking != null && booking.getStatus() == BookingStage.SEARCHING) {
                String serviceName = booking.getService() != null ? booking.getService().getName() : "Home Service";

                requests.add(com.homeease.backend.dto.WorkerDto.WorkerBookingRequestResponse.builder()
                        .bookingId(booking.getBookingId())
                        .status(booking.getStatus().name())
                        .serviceName(serviceName)
                        .subServiceName(serviceName)
                        .customerName(booking.getUser() != null ? booking.getUser().getFullName() : "Customer")
                        .customerPhone(booking.getUser() != null ? booking.getUser().getPhoneNumber() : "")
                        .userLat(booking.getUserLat())
                        .userLng(booking.getUserLng())
                        .totalAmount(booking.getTotalAmount())
                        .scheduledAt(booking.getScheduledAt())
                        .createdAt(booking.getCreatedAt())
                        .build());
            }
        }
        return requests;
    }
}
