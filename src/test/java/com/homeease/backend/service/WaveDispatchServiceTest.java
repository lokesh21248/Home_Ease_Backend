package com.homeease.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.homeease.backend.model.entity.Booking;
import com.homeease.backend.model.entity.BookingAssignment;
import com.homeease.backend.model.entity.User;
import com.homeease.backend.model.entity.Worker;
import com.homeease.backend.model.enums.AssignmentStatus;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.BookingType;
import com.homeease.backend.repository.AssignmentWeightConfigRepository;
import com.homeease.backend.repository.BookingAssignmentRepository;
import com.homeease.backend.repository.BookingRepository;
import com.homeease.backend.repository.WorkerRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

/**
 * Two ownership rules for the wave-dispatch engine, now that offers are actually persisted:
 * <ul>
 *   <li>its scheduled scanner advances only the offers THIS engine made (they carry a score); the standard engine's
 *       unscored offers belong to {@code BookingAssignmentService.processExpiredAssignments};</li>
 *   <li>its offers are announced to workers only after the transaction that wrote them has committed.</li>
 * </ul>
 */
class WaveDispatchServiceTest {

    private BookingRepository bookingRepository;
    private WorkerRepository workerRepository;
    private BookingAssignmentRepository assignmentRepository;
    private WorkerStreamService workerStreamService;
    private AssignmentScorer assignmentScorer;
    private FcmNotificationService fcmNotificationService;
    private WaveDispatchService service;

    private Booking booking;

    @BeforeEach
    void setUp() {
        bookingRepository = mock(BookingRepository.class);
        workerRepository = mock(WorkerRepository.class);
        assignmentRepository = mock(BookingAssignmentRepository.class);
        workerStreamService = mock(WorkerStreamService.class);
        assignmentScorer = mock(AssignmentScorer.class);
        fcmNotificationService = mock(FcmNotificationService.class);
        service = new WaveDispatchService(bookingRepository, workerRepository, assignmentRepository,
                workerStreamService, assignmentScorer, mock(AssignmentWeightConfigRepository.class), fcmNotificationService);

        booking = Booking.builder()
                .bookingId(UUID.randomUUID())
                .bookingType(BookingType.INSTANT)
                .status(BookingStage.SEARCHING)
                .userLat(17.4486)
                .userLng(78.3908)
                .servicePincode("500081")
                .build();
    }

    @AfterEach
    void leaveNoTransactionBehind() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private BookingAssignment expiredOffer(Double score) {
        return BookingAssignment.builder()
                .assignmentId(UUID.randomUUID())
                .booking(booking)
                .worker(Worker.builder().workerId(UUID.randomUUID()).build())
                .status(AssignmentStatus.PENDING)
                .attemptNo(1)
                .score(score)
                .expiresAt(Instant.now().minusSeconds(30))
                .build();
    }

    @Test
    @DisplayName("scanner leaves the standard engine's unscored offers alone (they are timed out and cascaded by BookingAssignmentService)")
    void scanner_ignoresOffersFromTheStandardEngine() {
        BookingAssignment standardEngineOffer = expiredOffer(null);
        // the query already selects only scored offers; even if a booking is listed, the offers read under its lock
        // are filtered again, so an unscored one is never touched
        when(assignmentRepository.findBookingIdsWithExpiredWaveOffers(any(Instant.class)))
                .thenReturn(List.of(booking.getBookingId()));
        when(bookingRepository.findByIdForUpdate(booking.getBookingId())).thenReturn(Optional.of(booking));
        when(assignmentRepository.findByBooking_BookingIdAndStatus(booking.getBookingId(), AssignmentStatus.PENDING))
                .thenReturn(List.of(standardEngineOffer));

        service.scanAndAdvanceRounds();

        assertThat(standardEngineOffer.getStatus()).isEqualTo(AssignmentStatus.PENDING);
        assertThat(standardEngineOffer.getRespondedAt()).isNull();
        verify(assignmentRepository, never()).save(any(BookingAssignment.class));
        verify(bookingRepository, never()).save(any(Booking.class));
        verify(assignmentScorer, never()).rankCandidates(any(), any(), any());
    }

    @Test
    @DisplayName("scanner still times out and advances offers made by the wave engine itself")
    void scanner_advancesOffersFromTheWaveEngine() {
        BookingAssignment waveOffer = expiredOffer(0.82);
        when(assignmentRepository.findBookingIdsWithExpiredWaveOffers(any(Instant.class)))
                .thenReturn(List.of(booking.getBookingId()));
        when(bookingRepository.findByIdForUpdate(booking.getBookingId())).thenReturn(Optional.of(booking));
        // read under the booking lock: first the expired offer, then (after it is timed out) nothing left pending
        when(assignmentRepository.findByBooking_BookingIdAndStatus(booking.getBookingId(), AssignmentStatus.PENDING))
                .thenReturn(List.of(waveOffer))
                .thenReturn(Collections.emptyList());
        when(assignmentRepository.findByBooking_BookingId(booking.getBookingId())).thenReturn(List.of(waveOffer));
        when(workerStreamService.findActiveWorkersInZone(any(), anyDouble(), anyDouble(), anyDouble())).thenReturn(new ArrayList<>());
        when(workerRepository.findAll()).thenReturn(Collections.emptyList());
        when(assignmentScorer.rankCandidates(any(), any(), any())).thenReturn(Collections.emptyList());

        service.scanAndAdvanceRounds();

        assertThat(waveOffer.getStatus()).isEqualTo(AssignmentStatus.TIMEOUT);
        verify(assignmentScorer, atLeastOnce()).rankCandidates(any(), any(), any());
        assertThat(booking.getStatus()).as("rounds 2 and 3 found nobody").isEqualTo(BookingStage.WORKER_NOT_FOUND);
    }

    @Test
    @DisplayName("a wave offer is announced to the worker only after the transaction that wrote it commits, and never if it rolls back")
    @SuppressWarnings("unchecked")
    void waveOffers_areAnnouncedOnlyAfterCommit() {
        UUID workerUserId = UUID.randomUUID();
        Worker worker = Worker.builder().workerId(UUID.randomUUID()).user(User.builder().userId(workerUserId).build()).build();
        when(workerStreamService.findActiveWorkersInZone(any(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new ArrayList<>(List.of(worker.getWorkerId())));
        when(workerRepository.findById(worker.getWorkerId())).thenReturn(Optional.of(worker));
        when(assignmentRepository.findByBooking_BookingId(booking.getBookingId())).thenReturn(Collections.emptyList());
        when(assignmentScorer.rankCandidates(any(), any(), any())).thenReturn(List.of(
                AssignmentScorer.CandidateScore.builder().worker(worker).totalScore(0.8).build()));

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        service.dispatchRound(booking, null, 1);

        verify(assignmentRepository).save(any(BookingAssignment.class));
        verify(workerStreamService, never()).pushOfferToWorkerStream(any(), any());
        verify(fcmNotificationService, never()).sendPushNotification(any(), any(), any());

        TransactionSynchronizationUtils.triggerAfterCommit();
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(workerStreamService).pushOfferToWorkerStream(eq(worker.getWorkerId()), payload.capture());
        assertThat(payload.getValue()).containsEntry("type", "NEW_OFFER").containsEntry("bookingId", booking.getBookingId().toString());
        verify(fcmNotificationService).sendPushNotification(eq(workerUserId), any(), any());
    }

    @Test
    @DisplayName("a rolled-back wave round announces nothing")
    void waveOffers_areNeverAnnouncedWhenTheTransactionRollsBack() {
        Worker worker = Worker.builder().workerId(UUID.randomUUID()).user(User.builder().userId(UUID.randomUUID()).build()).build();
        when(workerStreamService.findActiveWorkersInZone(any(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new ArrayList<>(List.of(worker.getWorkerId())));
        when(workerRepository.findById(worker.getWorkerId())).thenReturn(Optional.of(worker));
        when(assignmentRepository.findByBooking_BookingId(booking.getBookingId())).thenReturn(Collections.emptyList());
        when(assignmentScorer.rankCandidates(any(), any(), any())).thenReturn(List.of(
                AssignmentScorer.CandidateScore.builder().worker(worker).totalScore(0.8).build()));

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        service.dispatchRound(booking, null, 1);
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(workerStreamService, never()).pushOfferToWorkerStream(any(), any());
        verify(fcmNotificationService, never()).sendPushNotification(any(), any(), any());
    }
}
