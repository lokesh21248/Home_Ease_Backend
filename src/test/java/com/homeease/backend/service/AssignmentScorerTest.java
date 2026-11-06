package com.homeease.backend.service;

import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.repository.AssignmentWeightConfigRepository;
import com.homeease.backend.repository.BookingRepository;
import com.homeease.backend.repository.WorkerServiceLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssignmentScorerTest {

    @Mock
    private AssignmentWeightConfigRepository weightConfigRepository;

    @Mock
    private WorkerServiceLinkRepository workerServiceLinkRepository;

    @Mock
    private BookingRepository bookingRepository;

    private AssignmentScorer scorer;
    private AssignmentWeightConfig defaultConfig;

    @BeforeEach
    void setUp() {
        scorer = new AssignmentScorer(weightConfigRepository, workerServiceLinkRepository, bookingRepository);

        defaultConfig = AssignmentWeightConfig.builder()
                .distanceWeight(0.35)
                .ratingWeight(0.25)
                .acceptanceRateWeight(0.20)
                .completionRateWeight(0.10)
                .fairnessWeight(0.10)
                .maxSearchRadiusMeters(8000.0)
                .build();
    }

    @Test
    @DisplayName("Hard Filter: Rejects worker who is offline")
    void testHardFilter_RejectsOfflineWorker() {
        Worker worker = Worker.builder()
                .workerId(UUID.randomUUID())
                .isOnline(false)
                .isVerified(true)
                .build();
        Booking booking = Booking.builder().userLat(17.44).userLng(78.38).build();

        String reason = scorer.checkHardFilters(worker, booking, null, 8000.0);
        assertEquals("WORKER_OFFLINE", reason);
        assertFalse(scorer.passesHardFilters(worker, booking, null, 8000.0));
    }

    @Test
    @DisplayName("Hard Filter: Rejects worker who is unverified")
    void testHardFilter_RejectsUnverifiedWorker() {
        Worker worker = Worker.builder()
                .workerId(UUID.randomUUID())
                .isOnline(true)
                .isVerified(false)
                .build();
        Booking booking = Booking.builder().userLat(17.44).userLng(78.38).build();

        String reason = scorer.checkHardFilters(worker, booking, null, 8000.0);
        assertEquals("WORKER_UNVERIFIED", reason);
    }

    @Test
    @DisplayName("Hard Filter: Rejects worker with skill / trade mismatch")
    void testHardFilter_RejectsSkillMismatch() {
        UUID workerId = UUID.randomUUID();
        UUID targetSubServiceId = UUID.randomUUID();

        Worker worker = Worker.builder()
                .workerId(workerId)
                .isOnline(true)
                .isVerified(true)
                .currentLat(17.44)
                .currentLng(78.38)
                .build();
        Booking booking = Booking.builder().userLat(17.44).userLng(78.38).build();

        when(workerServiceLinkRepository.existsById_WorkerIdAndId_SubServiceId(workerId, targetSubServiceId))
                .thenReturn(false);
        when(workerServiceLinkRepository.existsByWorkerIdAndSubServiceOrServiceId(workerId, targetSubServiceId))
                .thenReturn(false);

        String reason = scorer.checkHardFilters(worker, booking, targetSubServiceId, 8000.0);
        assertEquals("SKILL_MISMATCH", reason);
    }

    @Test
    @DisplayName("Hard Filter: Rejects worker with active slot window conflict")
    void testHardFilter_RejectsSlotConflict() {
        UUID workerId = UUID.randomUUID();
        Instant slotStart = Instant.parse("2026-11-10T10:00:00Z");
        Instant slotEnd = Instant.parse("2026-11-10T11:00:00Z");

        Worker worker = Worker.builder()
                .workerId(workerId)
                .isOnline(true)
                .isVerified(true)
                .currentLat(17.44)
                .currentLng(78.38)
                .build();

        Booking newBooking = Booking.builder()
                .userLat(17.44)
                .userLng(78.38)
                .scheduledAt(slotStart)
                .scheduledEndAt(slotEnd)
                .build();

        Booking conflictingBooking = Booking.builder()
                .scheduledAt(Instant.parse("2026-11-10T10:30:00Z"))
                .scheduledEndAt(Instant.parse("2026-11-10T11:30:00Z"))
                .status(BookingStage.CONFIRMED)
                .build();

        when(bookingRepository.findByWorker_WorkerIdAndStatusInOrderByScheduledAtAsc(eq(workerId), any()))
                .thenReturn(List.of(conflictingBooking));

        String reason = scorer.checkHardFilters(worker, newBooking, null, 8000.0);
        assertEquals("SLOT_OVERLAP_CONFLICT", reason);
    }

    @Test
    @DisplayName("Hard Filter: Rejects worker beyond maximum configured radius")
    void testHardFilter_RejectsOutOfRadius() {
        Worker worker = Worker.builder()
                .workerId(UUID.randomUUID())
                .isOnline(true)
                .isVerified(true)
                .currentLat(17.55) // ~15km away from 17.44
                .currentLng(78.38)
                .build();

        Booking booking = Booking.builder().userLat(17.44).userLng(78.38).build();

        String reason = scorer.checkHardFilters(worker, booking, null, 5000.0);
        assertEquals("OUT_OF_RADIUS", reason);
    }

    @Test
    @DisplayName("Scorer: Closer worker receives strictly higher distance score than further worker")
    void testScorer_DistanceDecay() {
        Booking booking = Booking.builder().userLat(17.4400).userLng(78.3800).build();

        // Worker A: ~500m away
        Worker workerClose = Worker.builder()
                .workerId(UUID.randomUUID())
                .currentLat(17.4445)
                .currentLng(78.3800)
                .rating(4.5)
                .build();

        // Worker B: ~4000m away
        Worker workerFar = Worker.builder()
                .workerId(UUID.randomUUID())
                .currentLat(17.4760)
                .currentLng(78.3800)
                .rating(4.5)
                .build();

        AssignmentScorer.CandidateScore scoreClose = scorer.scoreWorker(workerClose, booking, defaultConfig);
        AssignmentScorer.CandidateScore scoreFar = scorer.scoreWorker(workerFar, booking, defaultConfig);

        assertTrue(scoreClose.getDistanceScore() > scoreFar.getDistanceScore(),
                "Closer worker must achieve higher distance score");
        assertTrue(scoreClose.getTotalScore() > scoreFar.getTotalScore(),
                "Closer worker must achieve higher total score when other factors equal");
    }

    @Test
    @DisplayName("Scorer: High rated worker scores higher than low rated worker at same distance")
    void testScorer_RatingImpact() {
        Booking booking = Booking.builder().userLat(17.4400).userLng(78.3800).build();

        Worker worker5Star = Worker.builder()
                .workerId(UUID.randomUUID())
                .currentLat(17.4450)
                .currentLng(78.3800)
                .rating(5.0)
                .build();

        Worker worker3Star = Worker.builder()
                .workerId(UUID.randomUUID())
                .currentLat(17.4450)
                .currentLng(78.3800)
                .rating(3.0)
                .build();

        AssignmentScorer.CandidateScore score5 = scorer.scoreWorker(worker5Star, booking, defaultConfig);
        AssignmentScorer.CandidateScore score3 = scorer.scoreWorker(worker3Star, booking, defaultConfig);

        assertEquals(1.0, score5.getRatingScore(), 0.001);
        assertEquals(0.5, score3.getRatingScore(), 0.001);
        assertTrue(score5.getTotalScore() > score3.getTotalScore());
    }
}

