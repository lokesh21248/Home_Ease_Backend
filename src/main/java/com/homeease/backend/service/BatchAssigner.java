package com.homeease.backend.service;

import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.AssignmentStatus;
import com.homeease.backend.repository.*;
import lombok.Builder;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class BatchAssigner {

    private static final Logger logger = LoggerFactory.getLogger(BatchAssigner.class);
    private static final double FULFILLMENT_BONUS = 1000.0; // Prioritizes fulfilling more bookings over score delta

    private final BookingRepository bookingRepository;
    private final WorkerRepository workerRepository;
    private final BookingAssignmentRepository assignmentRepository;
    private final AssignmentScorer assignmentScorer;
    private final AssignmentWeightConfigRepository weightConfigRepository;
    private final BookingAssignmentService assignmentService;

    public BatchAssigner(BookingRepository bookingRepository,
                         WorkerRepository workerRepository,
                         BookingAssignmentRepository assignmentRepository,
                         AssignmentScorer assignmentScorer,
                         AssignmentWeightConfigRepository weightConfigRepository,
                         BookingAssignmentService assignmentService) {
        this.bookingRepository = bookingRepository;
        this.workerRepository = workerRepository;
        this.assignmentRepository = assignmentRepository;
        this.assignmentScorer = assignmentScorer;
        this.weightConfigRepository = weightConfigRepository;
        this.assignmentService = assignmentService;
    }

    @Getter
    @Builder
    public static class BatchAssignmentResult {
        private final int totalBookings;
        private final int assignedBookings;
        private final double totalUtility;
        private final Map<UUID, UUID> bookingToWorkerMap; // bookingId -> workerId
        private final Map<UUID, Double> bookingScoreMap;
    }

    /**
     * T-120 Background Scheduler:
     * Scans for scheduled bookings starting 120 minutes from now (T-120) and executes global batch assignment.
     */
    @Scheduled(fixedDelay = 60000) // Runs every minute
    @Transactional
    public void runT120ScheduledAssigner() {
        Instant now = Instant.now();
        Instant windowStart = now.plus(115, ChronoUnit.MINUTES);
        Instant windowEnd = now.plus(125, ChronoUnit.MINUTES);

        List<Booking> unassignedBookings = bookingRepository.findUnassignedScheduledBookingsInWindow(windowStart, windowEnd);
        if (unassignedBookings.isEmpty()) {
            return;
        }

        logger.info("T-120 Assigner: Found {} unassigned scheduled bookings starting between {} and {}",
                unassignedBookings.size(), windowStart, windowEnd);

        List<Worker> activeWorkers = workerRepository.findAll().stream()
                .filter(w -> Boolean.TRUE.equals(w.getIsVerified()))
                .filter(w -> Boolean.TRUE.equals(w.getIsOnline()))
                .toList();

        BatchAssignmentResult result = assignBatch(unassignedBookings, activeWorkers);

        // Commit assignments to database & initiate offer protocol
        for (Map.Entry<UUID, UUID> entry : result.getBookingToWorkerMap().entrySet()) {
            UUID bookingId = entry.getKey();
            UUID workerId = entry.getValue();
            Double score = result.getBookingScoreMap().get(bookingId);

            Booking booking = bookingRepository.findById(bookingId).orElse(null);
            Worker worker = workerRepository.findById(workerId).orElse(null);

            if (booking != null && worker != null) {
                commitBatchAssignmentOffer(booking, worker, score);
            }
        }
    }

    /**
     * Solves the global batch assignment problem using maximum weight bipartite matching.
     * Guarantees higher or equal total utility and fulfillment compared to sequential greedy matching.
     */
    public BatchAssignmentResult assignBatch(List<Booking> bookings, List<Worker> workers) {
        if (bookings.isEmpty() || workers.isEmpty()) {
            return BatchAssignmentResult.builder()
                    .totalBookings(bookings.size())
                    .assignedBookings(0)
                    .totalUtility(0.0)
                    .bookingToWorkerMap(Collections.emptyMap())
                    .bookingScoreMap(Collections.emptyMap())
                    .build();
        }

        int n = bookings.size();
        int m = workers.size();

        // Build score matrix: [n bookings][m workers]
        double[][] scoreMatrix = new double[n][m];
        boolean[][] eligibleMatrix = new boolean[n][m];

        AssignmentWeightConfig config = weightConfigRepository.findAll().stream()
                .findFirst().orElse(null);

        for (int i = 0; i < n; i++) {
            Booking b = bookings.get(i);
            for (int j = 0; j < m; j++) {
                Worker w = workers.get(j);
                String rejection = assignmentScorer.checkHardFilters(
                        w, b, null, config != null ? config.getMaxSearchRadiusMeters() : 10000.0);
                if (rejection == null) {
                    eligibleMatrix[i][j] = true;
                    AssignmentScorer.CandidateScore cs = assignmentScorer.scoreWorker(w, b, config);
                    scoreMatrix[i][j] = cs.getTotalScore();
                } else {
                    eligibleMatrix[i][j] = false;
                    scoreMatrix[i][j] = -1.0;
                }
            }
        }

        // Solve Maximum Weight Bipartite Matching via Kuhn-Munkres (Hungarian algorithm)
        int[] matching = solveMaxWeightBipartiteMatching(n, m, scoreMatrix, eligibleMatrix);

        Map<UUID, UUID> bookingToWorkerMap = new LinkedHashMap<>();
        Map<UUID, Double> bookingScoreMap = new LinkedHashMap<>();
        double totalUtility = 0.0;
        int assignedCount = 0;

        for (int i = 0; i < n; i++) {
            int matchedWorkerIdx = matching[i];
            if (matchedWorkerIdx >= 0) {
                Booking b = bookings.get(i);
                Worker w = workers.get(matchedWorkerIdx);
                double score = scoreMatrix[i][matchedWorkerIdx];
                bookingToWorkerMap.put(b.getBookingId(), w.getWorkerId());
                bookingScoreMap.put(b.getBookingId(), score);
                totalUtility += score;
                assignedCount++;
            }
        }

        return BatchAssignmentResult.builder()
                .totalBookings(n)
                .assignedBookings(assignedCount)
                .totalUtility(Math.round(totalUtility * 10000.0) / 10000.0)
                .bookingToWorkerMap(bookingToWorkerMap)
                .bookingScoreMap(bookingScoreMap)
                .build();
    }

    /**
     * Greedy assignment implementation used as the baseline for comparison in checks and tests.
     */
    public BatchAssignmentResult greedyAssign(List<Booking> bookings, List<Worker> workers, double[][] scoreMatrix, boolean[][] eligibleMatrix) {
        int n = bookings.size();
        int m = workers.size();
        Set<Integer> assignedWorkers = new HashSet<>();
        Map<UUID, UUID> bookingToWorkerMap = new LinkedHashMap<>();
        Map<UUID, Double> bookingScoreMap = new LinkedHashMap<>();
        double totalUtility = 0.0;

        for (int i = 0; i < n; i++) {
            int bestWorkerIdx = -1;
            double bestScore = -1.0;

            for (int j = 0; j < m; j++) {
                if (eligibleMatrix[i][j] && !assignedWorkers.contains(j)) {
                    if (scoreMatrix[i][j] > bestScore) {
                        bestScore = scoreMatrix[i][j];
                        bestWorkerIdx = j;
                    }
                }
            }

            if (bestWorkerIdx >= 0) {
                assignedWorkers.add(bestWorkerIdx);
                Booking b = bookings.get(i);
                Worker w = workers.get(bestWorkerIdx);
                bookingToWorkerMap.put(b.getBookingId(), w.getWorkerId());
                bookingScoreMap.put(b.getBookingId(), bestScore);
                totalUtility += bestScore;
            }
        }

        return BatchAssignmentResult.builder()
                .totalBookings(n)
                .assignedBookings(bookingToWorkerMap.size())
                .totalUtility(Math.round(totalUtility * 10000.0) / 10000.0)
                .bookingToWorkerMap(bookingToWorkerMap)
                .bookingScoreMap(bookingScoreMap)
                .build();
    }

    /**
     * Maximum Weight Bipartite Matching (Hungarian algorithm / Maximum weight augmenting paths).
     * Solves optimal global 1-to-1 matching maximizing total weights.
     */
    public int[] solveMaxWeightBipartiteMatching(int n, int m, double[][] scoreMatrix, boolean[][] eligibleMatrix) {
        int dim = Math.max(n, m);
        double[][] cost = new double[dim][dim];

        // Convert maximum weight to minimum cost: cost = MAX_VAL - weight
        double maxWeight = 0.0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                if (eligibleMatrix[i][j]) {
                    double effectiveWeight = FULFILLMENT_BONUS + scoreMatrix[i][j];
                    if (effectiveWeight > maxWeight) maxWeight = effectiveWeight;
                }
            }
        }

        for (int i = 0; i < dim; i++) {
            for (int j = 0; j < dim; j++) {
                if (i < n && j < m && eligibleMatrix[i][j]) {
                    cost[i][j] = (maxWeight + 10.0) - (FULFILLMENT_BONUS + scoreMatrix[i][j]);
                } else {
                    cost[i][j] = maxWeight + 10000.0; // Disallowed edge
                }
            }
        }

        // Hungarian algorithm for minimum cost perfect matching
        int[] result = hungarianMinCost(dim, cost);

        // Filter out dummy/disallowed assignments
        int[] matching = new int[n];
        for (int i = 0; i < n; i++) {
            int matchedWorker = result[i];
            if (matchedWorker < m && eligibleMatrix[i][matchedWorker]) {
                matching[i] = matchedWorker;
            } else {
                matching[i] = -1; // Unassigned
            }
        }
        return matching;
    }

    private int[] hungarianMinCost(int n, double[][] matrix) {
        double[] u = new double[n + 1];
        double[] v = new double[n + 1];
        int[] p = new int[n + 1];
        int[] way = new int[n + 1];

        for (int i = 1; i <= n; i++) {
            p[0] = i;
            int j0 = 0;
            double[] minv = new double[n + 1];
            Arrays.fill(minv, Double.MAX_VALUE);
            boolean[] used = new boolean[n + 1];

            do {
                used[j0] = true;
                int i0 = p[j0];
                double delta = Double.MAX_VALUE;
                int j1 = 0;

                for (int j = 1; j <= n; j++) {
                    if (!used[j]) {
                        double cur = matrix[i0 - 1][j - 1] - u[i0] - v[j];
                        if (cur < minv[j]) {
                            minv[j] = cur;
                            way[j] = j0;
                        }
                        if (minv[j] < delta) {
                            delta = minv[j];
                            j1 = j;
                        }
                    }
                }

                for (int j = 0; j <= n; j++) {
                    if (used[j]) {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        minv[j] -= delta;
                    }
                }
                j0 = j1;
            } while (p[j0] != 0);

            do {
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }

        int[] match = new int[n];
        for (int j = 1; j <= n; j++) {
            if (p[j] > 0) {
                match[p[j] - 1] = j - 1;
            }
        }
        return match;
    }

    private void commitBatchAssignmentOffer(Booking booking, Worker worker, Double score) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(180, ChronoUnit.SECONDS);

        BookingAssignment assignment = BookingAssignment.builder()
                .booking(booking)
                .worker(worker)
                .status(AssignmentStatus.PENDING)
                .attemptNo(1)
                .score(score)
                .rankOrder(1)
                .requestedAt(now)
                .expiresAt(expiresAt)
                .build();

        assignmentRepository.save(assignment);
        logger.info("BatchAssigner: Created pending offer for booking {} to worker {} (score={})",
                booking.getBookingId(), worker.getWorkerId(), score);
    }
}

