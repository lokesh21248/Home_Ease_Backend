package com.homeease.backend.service;

import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.repository.AssignmentWeightConfigRepository;
import com.homeease.backend.repository.BookingRepository;
import com.homeease.backend.repository.WorkerServiceLinkRepository;
import lombok.Builder;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Component
public class AssignmentScorer {

    private static final Logger logger = LoggerFactory.getLogger(AssignmentScorer.class);
    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    private final AssignmentWeightConfigRepository weightConfigRepository;
    private final WorkerServiceLinkRepository workerServiceLinkRepository;
    private final BookingRepository bookingRepository;

    public AssignmentScorer(AssignmentWeightConfigRepository weightConfigRepository,
            WorkerServiceLinkRepository workerServiceLinkRepository,
            BookingRepository bookingRepository) {
        this.weightConfigRepository = weightConfigRepository;
        this.workerServiceLinkRepository = workerServiceLinkRepository;
        this.bookingRepository = bookingRepository;
    }

    @Getter
    @Builder
    public static class CandidateScore {
        private final Worker worker;
        private final double totalScore;
        private final double distanceScore;
        private final double ratingScore;
        private final double acceptanceScore;
        private final double completionScore;
        private final double fairnessScore;
        private final double distanceMeters;
        private final boolean eligible;
        private final String rejectionReason;
    }

    /**
     * Evaluates hard filters for a candidate worker against a booking.
     */
    public boolean passesHardFilters(Worker worker, Booking booking, UUID targetSubServiceId, double maxRadiusMeters) {
        return checkHardFilters(worker, booking, targetSubServiceId, maxRadiusMeters) == null;
    }

    public String checkHardFilters(Worker worker, Booking booking, UUID targetSubServiceId, double maxRadiusMeters) {
        if (worker == null)
            return "WORKER_NULL";

        // 1. Online filter
        if (!Boolean.TRUE.equals(worker.getIsOnline())) {
            return "WORKER_OFFLINE";
        }

        // 2. Verification filter
        if (!Boolean.TRUE.equals(worker.getIsVerified())) {
            return "WORKER_UNVERIFIED";
        }

        // 3. Penalty / Blocked filter
        if (worker.getBlockedUntil() != null && worker.getBlockedUntil().isAfter(Instant.now())) {
            return "WORKER_BLOCKED";
        }

        // 4. Location coordinates validity
        if (worker.getCurrentLat() == null || worker.getCurrentLng() == null) {
            return "LOCATION_MISSING";
        }
        if (Math.abs(worker.getCurrentLat()) > 90.0 || Math.abs(worker.getCurrentLng()) > 180.0 ||
                (worker.getCurrentLat() == 0.0 && worker.getCurrentLng() == 0.0)) {
            return "LOCATION_INVALID";
        }

        // 5. Category / skill qualification filter
        UUID serviceId = booking.getService() != null ? booking.getService().getServiceId() : null;
        if (targetSubServiceId != null || serviceId != null) {
            boolean qualified = false;
            if (targetSubServiceId != null) {
                qualified = workerServiceLinkRepository.existsById_WorkerIdAndId_SubServiceId(worker.getWorkerId(),
                        targetSubServiceId)
                        || workerServiceLinkRepository.existsByWorkerIdAndSubServiceOrServiceId(worker.getWorkerId(),
                                targetSubServiceId);
            }
            if (!qualified && serviceId != null) {
                qualified = workerServiceLinkRepository.existsByWorkerIdAndServiceId(worker.getWorkerId(), serviceId);
            }
            if (!qualified) {
                return "SKILL_MISMATCH";
            }
        }

        // 6. Active job conflict filter (for instant: any active stage; for slot:
        // overlapping scheduled time)
        List<BookingStage> activeStages = List.of(
                BookingStage.ACCEPTED, BookingStage.CONFIRMED,
                BookingStage.ON_THE_WAY, BookingStage.ARRIVED, BookingStage.IN_PROGRESS);
        List<Booking> activeBookings = bookingRepository.findByWorker_WorkerIdAndStatusInOrderByScheduledAtAsc(
                worker.getWorkerId(), activeStages);

        if (!activeBookings.isEmpty()) {
            if (booking.getScheduledAt() == null) {
                // Instant booking: cannot assign to worker with an active in-flight job
                return "WORKER_BUSY_ACTIVE_JOB";
            } else {
                // Scheduled slot: check window overlap (e.g. ±1 hour)
                Instant slotStart = booking.getScheduledAt();
                Instant slotEnd = booking.getScheduledEndAt() != null
                        ? booking.getScheduledEndAt()
                        : slotStart.plusSeconds(3600);
                for (Booking ab : activeBookings) {
                    if (ab.getScheduledAt() != null) {
                        Instant bStart = ab.getScheduledAt();
                        Instant bEnd = ab.getScheduledEndAt() != null ? ab.getScheduledEndAt()
                                : bStart.plusSeconds(3600);
                        if (slotStart.isBefore(bEnd) && slotEnd.isAfter(bStart)) {
                            return "SLOT_OVERLAP_CONFLICT";
                        }
                    }
                }
            }
        }

        // 7. Distance threshold
        double distance = BookingAssignmentService.calculateHaversineDistance(
                booking.getUserLat(), booking.getUserLng(),
                worker.getCurrentLat(), worker.getCurrentLng());
        if (distance > maxRadiusMeters) {
            return "OUT_OF_RADIUS";
        }

        return null; // Passed all hard filters
    }

    /**
     * Computes the normalized multi-factor score for an eligible worker.
     */
    public CandidateScore scoreWorker(Worker worker, Booking booking, AssignmentWeightConfig config) {
        double maxRadius = config != null ? config.getMaxSearchRadiusMeters() : 8000.0;
        double wDist = config != null ? config.getDistanceWeight() : 0.35;
        double wRating = config != null ? config.getRatingWeight() : 0.25;
        double wAccept = config != null ? config.getAcceptanceRateWeight() : 0.20;
        double wComp = config != null ? config.getCompletionRateWeight() : 0.10;
        double wFair = config != null ? config.getFairnessWeight() : 0.10;

        // 1. Distance Score: linearly decays with distance (1.0 at 0m, 0.0 at
        // maxRadius)
        double distanceMeters = BookingAssignmentService.calculateHaversineDistance(
                booking.getUserLat(), booking.getUserLng(),
                worker.getCurrentLat() != null ? worker.getCurrentLat() : booking.getUserLat(),
                worker.getCurrentLng() != null ? worker.getCurrentLng() : booking.getUserLng());
        double sDist = Math.max(0.0, 1.0 - (distanceMeters / maxRadius));

        // 2. Rating Score: normalized from [1.0, 5.0] to [0.0, 1.0], default 4.5 prior
        double rawRating = worker.getRating() != null ? worker.getRating() : 4.5;
        double sRating = Math.max(0.0, Math.min(1.0, (rawRating - 1.0) / 4.0));

        // 3. Acceptance Rate Score: default 0.85 prior
        double sAccept = 0.85;

        // 4. Completion Rate Score: default 0.95 prior
        double sComp = 0.95;

        // 5. Fairness / Idle balancing Score:
        // Workers with fewer jobs today receive higher priority to prevent idle
        // starvation
        int jobsToday = countJobsCompletedToday(worker.getWorkerId());
        int targetDailyJobs = 6;
        double sFair = Math.max(0.0, 1.0 - ((double) jobsToday / targetDailyJobs));

        // Composite Weighted Score: bounded to [0.0, 1.0]
        double totalScore = (wDist * sDist) +
                (wRating * sRating) +
                (wAccept * sAccept) +
                (wComp * sComp) +
                (wFair * sFair);

        // Normalize score to 4 decimal places
        totalScore = Math.round(totalScore * 10000.0) / 10000.0;

        return CandidateScore.builder()
                .worker(worker)
                .totalScore(totalScore)
                .distanceScore(sDist)
                .ratingScore(sRating)
                .acceptanceScore(sAccept)
                .completionScore(sComp)
                .fairnessScore(sFair)
                .distanceMeters(distanceMeters)
                .eligible(true)
                .build();
    }

    /**
     * Filters, scores, and ranks candidate workers for a booking.
     */
    public List<CandidateScore> rankCandidates(List<Worker> candidatePool, Booking booking, UUID targetSubServiceId) {
        UUID serviceId = booking.getService() != null ? booking.getService().getServiceId() : null;
        String pincode = booking.getServicePincode() != null ? booking.getServicePincode() : "500072";

        AssignmentWeightConfig config = weightConfigRepository.findBestMatchingConfig(serviceId, pincode)
                .orElse(getDefaultConfig());

        List<CandidateScore> scoredList = new ArrayList<>();

        for (Worker worker : candidatePool) {
            String rejection = checkHardFilters(worker, booking, targetSubServiceId, config.getMaxSearchRadiusMeters());
            if (rejection == null) {
                CandidateScore score = scoreWorker(worker, booking, config);
                scoredList.add(score);
            } else {
                logger.debug("Worker {} rejected by hard filter: {}", worker.getWorkerId(), rejection);
            }
        }

        // Sort descending by totalScore (ties broken by shortest distance)
        scoredList.sort((a, b) -> {
            int cmp = Double.compare(b.getTotalScore(), a.getTotalScore());
            if (cmp != 0)
                return cmp;
            return Double.compare(a.getDistanceMeters(), b.getDistanceMeters());
        });

        return scoredList;
    }

    private int countJobsCompletedToday(UUID workerId) {
        try {
            LocalDate today = LocalDate.now(IST_ZONE);
            Instant startOfDay = today.atStartOfDay(IST_ZONE).toInstant();
            return bookingRepository.countCompletedJobsSince(workerId, startOfDay);
        } catch (Exception e) {
            return 0;
        }
    }

    private AssignmentWeightConfig getDefaultConfig() {
        return AssignmentWeightConfig.builder()
                .distanceWeight(0.35)
                .ratingWeight(0.25)
                .acceptanceRateWeight(0.20)
                .completionRateWeight(0.10)
                .fairnessWeight(0.10)
                .maxSearchRadiusMeters(8000.0)
                .offerTimeoutSeconds(60)
                .batchAssignTMinusMinutes(120)
                .build();
    }
}
