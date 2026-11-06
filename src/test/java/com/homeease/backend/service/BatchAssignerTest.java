package com.homeease.backend.service;

import com.homeease.backend.model.entity.*;
import com.homeease.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class BatchAssignerTest {

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private WorkerRepository workerRepository;

    @Mock
    private BookingAssignmentRepository assignmentRepository;

    @Mock
    private AssignmentScorer assignmentScorer;

    @Mock
    private AssignmentWeightConfigRepository weightConfigRepository;

    @Mock
    private BookingAssignmentService assignmentService;

    private BatchAssigner batchAssigner;

    @BeforeEach
    void setUp() {
        batchAssigner = new BatchAssigner(
                bookingRepository,
                workerRepository,
                assignmentRepository,
                assignmentScorer,
                weightConfigRepository,
                assignmentService
        );
    }

    @Test
    @DisplayName("Phase 2 Check: Batch Assigner beats greedy on tight-supply fixture")
    void testBatchAssigner_BeatsGreedyOnTightSupplyFixture() {
        // ── Fixture Definition ────────────────────────────────────────────────
        // Booking 1 and Booking 2
        UUID b1Id = UUID.randomUUID();
        UUID b2Id = UUID.randomUUID();
        Booking b1 = Booking.builder().bookingId(b1Id).userLat(17.44).userLng(78.38).build();
        Booking b2 = Booking.builder().bookingId(b2Id).userLat(17.45).userLng(78.39).build();
        List<Booking> bookings = List.of(b1, b2);

        // Worker 1 and Worker 2
        UUID w1Id = UUID.randomUUID();
        UUID w2Id = UUID.randomUUID();
        Worker w1 = Worker.builder().workerId(w1Id).isOnline(true).isVerified(true).build();
        Worker w2 = Worker.builder().workerId(w2Id).isOnline(true).isVerified(true).build();
        List<Worker> workers = List.of(w1, w2);

        // Hard filters & scores:
        // W1 is eligible for both B1 (score 0.90) and B2 (score 0.85)
        lenient().when(assignmentScorer.checkHardFilters(eq(w1), eq(b1), any(), anyDouble())).thenReturn(null);
        lenient().when(assignmentScorer.checkHardFilters(eq(w1), eq(b2), any(), anyDouble())).thenReturn(null);

        lenient().when(assignmentScorer.scoreWorker(eq(w1), eq(b1), any())).thenReturn(
                AssignmentScorer.CandidateScore.builder().worker(w1).totalScore(0.90).build()
        );
        lenient().when(assignmentScorer.scoreWorker(eq(w1), eq(b2), any())).thenReturn(
                AssignmentScorer.CandidateScore.builder().worker(w1).totalScore(0.85).build()
        );

        // W2 is eligible ONLY for B1 (score 0.70), but NOT eligible for B2 (e.g. out of radius / trade mismatch)
        lenient().when(assignmentScorer.checkHardFilters(eq(w2), eq(b1), any(), anyDouble())).thenReturn(null);
        lenient().when(assignmentScorer.checkHardFilters(eq(w2), eq(b2), any(), anyDouble())).thenReturn("SKILL_MISMATCH");

        lenient().when(assignmentScorer.scoreWorker(eq(w2), eq(b1), any())).thenReturn(
                AssignmentScorer.CandidateScore.builder().worker(w2).totalScore(0.70).build()
        );

        // Matrix form:
        // Score:
        //       W1    W2
        // B1   0.90  0.70
        // B2   0.85  -1.0 (ineligible)
        double[][] scoreMatrix = new double[][] {
                { 0.90, 0.70 },
                { 0.85, -1.0 }
        };
        boolean[][] eligibleMatrix = new boolean[][] {
                { true, true },
                { true, false }
        };

        // ── 1. Greedy Execution ───────────────────────────────────────────────
        // Greedy takes B1 first: picks highest scoring worker W1 (0.90).
        // Then takes B2: W1 is already assigned, W2 is ineligible. B2 is starved.
        BatchAssigner.BatchAssignmentResult greedyResult = batchAssigner.greedyAssign(bookings, workers, scoreMatrix, eligibleMatrix);

        assertEquals(1, greedyResult.getAssignedBookings(), "Greedy must only fulfill 1 booking");
        assertEquals(0.90, greedyResult.getTotalUtility(), 0.001);
        assertEquals(w1Id, greedyResult.getBookingToWorkerMap().get(b1Id));
        assertNull(greedyResult.getBookingToWorkerMap().get(b2Id), "Greedy leaves B2 unassigned");

        // ── 2. Batch Assigner (Global Optimal) Execution ───────────────────────
        // Global optimization realizes that B1 can be served by W2 (0.70) while B2 is served by W1 (0.85),
        // fulfilling BOTH bookings with a total utility of 1.55!
        BatchAssigner.BatchAssignmentResult batchResult = batchAssigner.assignBatch(bookings, workers);

        assertEquals(2, batchResult.getAssignedBookings(), "Batch Assigner must fulfill both bookings");
        assertEquals(1.55, batchResult.getTotalUtility(), 0.001);
        assertEquals(w2Id, batchResult.getBookingToWorkerMap().get(b1Id), "B1 must be assigned to W2");
        assertEquals(w1Id, batchResult.getBookingToWorkerMap().get(b2Id), "B2 must be assigned to W1");

        // ── 3. Assert Phase 2 Check Condition ────────────────────────────────
        assertTrue(batchResult.getAssignedBookings() > greedyResult.getAssignedBookings(),
                "Batch assigner fulfillment count must strictly beat greedy");
        assertTrue(batchResult.getTotalUtility() > greedyResult.getTotalUtility(),
                "Batch assigner total utility must strictly beat greedy on tight-supply fixture");
    }

    @Test
    @DisplayName("Batch Assigner handles empty inputs gracefully")
    void testBatchAssigner_EmptyInputs() {
        BatchAssigner.BatchAssignmentResult res1 = batchAssigner.assignBatch(List.of(), List.of());
        assertEquals(0, res1.getAssignedBookings());
        assertEquals(0.0, res1.getTotalUtility());
    }
}

