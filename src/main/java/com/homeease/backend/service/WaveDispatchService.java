package com.homeease.backend.service;

import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.*;
import com.homeease.backend.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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

    /** Gives post-commit offers their own transaction (FCM writes a row). Optional: without one they run inline. */
    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;

    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
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

        UUID workerId = worker.getWorkerId();
        UUID workerUserId = worker.getUser() != null ? worker.getUser().getUserId() : null;

        // Only after the offer row has been committed (see AfterCommit): a rolled-back offer must never be announced.
        AfterCommit.run(transactionManager, "wave offer for booking " + booking.getBookingId(), () -> {
            workerStreamService.pushOfferToWorkerStream(workerId, payload);

            if (workerUserId != null) {
                try {
                    fcmNotificationService.sendPushNotification(
                            workerUserId,
                            "New Job Offer (Round " + round + ")",
                            "Immediate job offer for " + serviceName + ". Tap to accept!"
                    );
                } catch (Exception ignored) {}
            }
        });
    }

    /**
     * Scheduled scanner running every 5 seconds to advance expired rounds to the next wave.
     */
    @Scheduled(fixedDelay = 5000)
    public void scanAndAdvanceRounds() {
        Instant now = Instant.now();
        // Only offers made by THIS engine (they carry a score and rank; see dispatchRound and BatchAssigner). Offers
        // created by BookingAssignmentService.dispatchToBatch have no score and are timed out and cascaded by
        // BookingAssignmentService.processExpiredAssignments with that engine's own matching rules. This scanner used
        // to claim every expired PENDING offer, so after the first batch it silently replaced the standard engine
        // with this one (different candidate source, 20 s rounds, heartbeat-only) for every booking.
        List<UUID> bookingIds = assignmentRepository.findBookingIdsWithExpiredWaveOffers(now);
        if (bookingIds == null || bookingIds.isEmpty()) return;

        // One short transaction per booking: a failure affects only that booking and is retried on the next scan.
        for (UUID bookingId : bookingIds) {
            try {
                inOwnTransaction(() -> expireAndAdvance(bookingId, now));
            } catch (Exception e) {
                logger.error("Wave round advance failed for booking {}: {}", bookingId, e.getMessage(), e);
            }
        }
    }

    /**
     * LOCK ORDER: the booking row first, then its offers, the same order acceptance uses. Updating the offers first
     * and taking the booking afterwards made this scanner and a worker accepting at the moment of expiry wait for each
     * other's row, and PostgreSQL aborted one of them with "deadlock detected". The offers are read afresh under the
     * lock, so an offer accepted a moment ago is never overwritten with TIMEOUT.
     */
    private void expireAndAdvance(UUID bookingId, Instant now) {
        Booking b = bookingRepository.findByIdForUpdate(bookingId).orElse(null);
        if (b == null) return;

        List<BookingAssignment> expired = assignmentRepository
                .findByBooking_BookingIdAndStatus(bookingId, AssignmentStatus.PENDING).stream()
                .filter(a -> a.getScore() != null && a.getExpiresAt() != null && a.getExpiresAt().isBefore(now))
                .toList();
        if (expired.isEmpty()) return;

        for (BookingAssignment ba : expired) {
            ba.setStatus(AssignmentStatus.TIMEOUT);
            ba.setRespondedAt(now);
            assignmentRepository.save(ba);
        }

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
    }

    /** Runs {@code work} in its own transaction (inline when no transaction manager is wired, as in unit tests). */
    private void inOwnTransaction(Runnable work) {
        if (transactionManager == null) {
            work.run();
            return;
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    private void handleDispatchExhausted(Booking booking) {
        logger.warn("Exhausted all 3 dispatch rounds (3km, 5km, 8km) for Booking {}. Marking WORKER_NOT_FOUND.",
                booking.getBookingId());
        booking.setStatus(BookingStage.WORKER_NOT_FOUND);
        bookingRepository.save(booking);
    }
}

