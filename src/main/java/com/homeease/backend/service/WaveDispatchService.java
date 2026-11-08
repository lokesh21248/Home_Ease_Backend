package com.homeease.backend.service;

import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.*;
import com.homeease.backend.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class WaveDispatchService {

    private static final Logger logger = LoggerFactory.getLogger(WaveDispatchService.class);

    // Wave dispatch rounds (3 km → 5 km → 8 km) per zone config
    public static final double[] DISPATCH_ROUNDS_METERS = {3000.0, 5000.0, 8000.0};
    public static final int ROUND_TIMEOUT_SECONDS = 20;

    private final BookingRepository bookingRepository;
    private final WorkerRepository workerRepository;
    private final BookingAssignmentRepository assignmentRepository;
    private final WorkerStreamService workerStreamService;
    private final AssignmentScorer assignmentScorer;
    private final AssignmentWeightConfigRepository weightConfigRepository;
    private final FcmNotificationService fcmNotificationService;

    public WaveDispatchService(BookingRepository bookingRepository,
                               WorkerRepository workerRepository,
                               BookingAssignmentRepository assignmentRepository,
                               WorkerStreamService workerStreamService,
                               AssignmentScorer assignmentScorer,
                               AssignmentWeightConfigRepository weightConfigRepository,
                               FcmNotificationService fcmNotificationService) {
        this.bookingRepository = bookingRepository;
        this.workerRepository = workerRepository;
        this.assignmentRepository = assignmentRepository;
        this.workerStreamService = workerStreamService;
        this.assignmentScorer = assignmentScorer;
        this.weightConfigRepository = weightConfigRepository;
        this.fcmNotificationService = fcmNotificationService;
    }

    /**
     * Initiates multi-round wave dispatch for an instant booking starting at Round 1 (3 km).
     */
    @Transactional
    public void startInstantWaveDispatch(Booking booking, UUID targetSubServiceId) {
        if (booking == null) return;

        booking.setStatus(BookingStage.SEARCHING);
        bookingRepository.save(booking);

        dispatchRound(booking, targetSubServiceId, 1);
    }

    /**
     * Executes a specific dispatch round (1 = 3km, 2 = 5km, 3 = 8km).
     */
    @Transactional
    public boolean dispatchRound(Booking booking, UUID targetSubServiceId, int roundNumber) {
        if (roundNumber > DISPATCH_ROUNDS_METERS.length) {
            // Exhausted all 3 rounds without worker acceptance
            handleDispatchExhausted(booking);
            return false;
        }

        double radiusMeters = DISPATCH_ROUNDS_METERS[roundNumber - 1];
        String zone = booking.getServicePincode();

        logger.info("Executing Wave Dispatch Round {} (radius={} m) for Booking {} in Zone {}",
                roundNumber, radiusMeters, booking.getBookingId(), zone);

        // 1. Query active workers in Redis GEO set with active 30s heartbeats
        List<UUID> activeWorkerIds = workerStreamService.findActiveWorkersInZone(
                zone, booking.getUserLat(), booking.getUserLng(), radiusMeters
        );

        // Fallback: if Redis GEO is empty, query Postgres active verified workers within radius
        if (activeWorkerIds.isEmpty()) {
            List<Worker> dbWorkers = workerRepository.findAll().stream()
                    .filter(w -> Boolean.TRUE.equals(w.getIsVerified()) && Boolean.TRUE.equals(w.getIsOnline()))
                    .filter(w -> {
                        if (w.getCurrentLat() == null || w.getCurrentLng() == null) return false;
                        double dist = BookingAssignmentService.calculateHaversineDistance(
                                booking.getUserLat(), booking.getUserLng(), w.getCurrentLat(), w.getCurrentLng());
                        return dist <= radiusMeters;
                    })
                    .toList();
            for (Worker w : dbWorkers) {
                activeWorkerIds.add(w.getWorkerId());
            }
        }

        // 2. Filter out workers who already received an assignment in earlier rounds
        List<BookingAssignment> pastAssignments = assignmentRepository.findByBooking_BookingId(booking.getBookingId());
        Set<UUID> previouslyAttemptedWorkers = new HashSet<>();
        for (BookingAssignment pa : pastAssignments) {
            previouslyAttemptedWorkers.add(pa.getWorker().getWorkerId());
        }

        List<Worker> freshCandidatePool = new ArrayList<>();
        for (UUID wid : activeWorkerIds) {
            if (!previouslyAttemptedWorkers.contains(wid)) {
                workerRepository.findById(wid).ifPresent(freshCandidatePool::add);
            }
        }

        // 3. Evaluate Hard Filters & Score Candidates via AssignmentScorer
        List<AssignmentScorer.CandidateScore> scoredCandidates = assignmentScorer.rankCandidates(
                freshCandidatePool, booking, targetSubServiceId
        );

        if (scoredCandidates.isEmpty()) {
            logger.info("Round {} found 0 eligible candidates within {} m. Cascading immediately to next round...",
                    roundNumber, radiusMeters);
            return dispatchRound(booking, targetSubServiceId, roundNumber + 1);
        }

        // 4. Dispatch offers to top candidate workers in this wave
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ROUND_TIMEOUT_SECONDS, ChronoUnit.SECONDS);
        int rank = 1;

        for (AssignmentScorer.CandidateScore cs : scoredCandidates) {
            Worker worker = cs.getWorker();

            BookingAssignment assignment = BookingAssignment.builder()
                    .booking(booking)
                    .worker(worker)
                    .status(AssignmentStatus.PENDING)
                    .attemptNo(roundNumber)
                    .score(cs.getTotalScore())
                    .rankOrder(rank++)
                    .requestedAt(now)
                    .expiresAt(expiresAt)
                    .build();

            assignmentRepository.save(assignment);

            // Push offer directly to /worker/stream and WebSocket requests
            sendOfferToWorker(worker, booking, roundNumber, cs.getTotalScore());
        }

        logger.info("Wave Dispatch Round {} successfully dispatched {} offers for Booking {}",
                roundNumber, scoredCandidates.size(), booking.getBookingId());
        return true;
    }

    private void sendOfferToWorker(Worker worker, Booking booking, int round, double score) {
        String serviceName = booking.getService() != null ? booking.getService().getName() : "Home Service";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "NEW_OFFER");
        payload.put("bookingId", booking.getBookingId().toString());
        payload.put("serviceName", serviceName);
        payload.put("round", round);
        payload.put("score", score);
        payload.put("totalAmount", booking.getTotalAmount());
        payload.put("expiresInSeconds", ROUND_TIMEOUT_SECONDS);
        payload.put("customerLat", booking.getUserLat());
        payload.put("customerLng", booking.getUserLng());
        payload.put("status", "SEARCHING");

        workerStreamService.pushOfferToWorkerStream(worker.getWorkerId(), payload);

        if (worker.getUser() != null) {
            try {
                fcmNotificationService.sendPushNotification(
                        worker.getUser().getUserId(),
                        "New Job Offer (Round " + round + ")",
                        "Immediate job offer for " + serviceName + ". Tap to accept!"
                );
            } catch (Exception ignored) {}
        }
    }

    /**
     * Scheduled scanner running every 5 seconds to advance expired rounds to the next wave.
     */
    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void scanAndAdvanceRounds() {
        Instant now = Instant.now();
        List<BookingAssignment> expiredAssignments = assignmentRepository.findByStatusAndExpiresAtBefore(
                AssignmentStatus.PENDING, now
        );

        if (expiredAssignments.isEmpty()) return;

        Set<UUID> bookingsToAdvance = new HashSet<>();
        for (BookingAssignment ba : expiredAssignments) {
            ba.setStatus(AssignmentStatus.TIMEOUT);
            ba.setRespondedAt(now);
            assignmentRepository.save(ba);
            if (ba.getBooking() != null) {
                bookingsToAdvance.add(ba.getBooking().getBookingId());
            }
        }

        for (UUID bookingId : bookingsToAdvance) {
            bookingRepository.findById(bookingId).ifPresent(b -> {
                if (b.getStatus() == BookingStage.SEARCHING || b.getStatus() == BookingStage.REQUESTED) {
                    List<BookingAssignment> activePending = assignmentRepository.findByBooking_BookingIdAndStatus(
                            bookingId, AssignmentStatus.PENDING
                    );
                    if (activePending.isEmpty()) {
                        List<BookingAssignment> all = assignmentRepository.findByBooking_BookingId(bookingId);
                        int maxAttempt = all.stream().mapToInt(BookingAssignment::getAttemptNo).max().orElse(1);
                        logger.info("Advancing booking {} from round {} to round {}", bookingId, maxAttempt, maxAttempt + 1);
                        dispatchRound(b, null, maxAttempt + 1);
                    }
                }
            });
        }
    }

    private void handleDispatchExhausted(Booking booking) {
        logger.warn("Exhausted all 3 dispatch rounds (3km, 5km, 8km) for Booking {}. Marking WORKER_NOT_FOUND.",
                booking.getBookingId());
        booking.setStatus(BookingStage.WORKER_NOT_FOUND);
        bookingRepository.save(booking);
    }
}

