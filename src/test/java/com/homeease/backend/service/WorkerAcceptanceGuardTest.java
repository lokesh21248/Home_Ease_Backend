package com.homeease.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.homeease.backend.exception.WorkerBusyException;
import com.homeease.backend.model.entity.Booking;
import com.homeease.backend.model.entity.User;
import com.homeease.backend.model.entity.Worker;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.BookingType;
import com.homeease.backend.repository.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * Logic of the one-in-flight-instant-job-per-worker rule. The advisory lock itself is Postgres behaviour and is
 * NOT exercised here (no database available): these tests prove the order of operations (lock first, then check),
 * the scope (instant bookings only), the kill switch, and that both accept paths call the guard before they write.
 */
class WorkerAcceptanceGuardTest {

    private WorkerRepository workerRepository;
    private BookingRepository bookingRepository;
    private WorkerAcceptanceGuard guard;
    private final UUID workerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        workerRepository = mock(WorkerRepository.class);
        bookingRepository = mock(BookingRepository.class);
        guard = new WorkerAcceptanceGuard(workerRepository, bookingRepository, true);
    }

    private Booking booking(BookingType type, BookingStage stage) {
        return Booking.builder().bookingId(UUID.randomUUID()).bookingType(type).status(stage).build();
    }

    @Test
    void a_free_worker_may_accept_an_instant_booking() {
        Booking target = booking(BookingType.INSTANT, BookingStage.SEARCHING);
        when(bookingRepository.findByWorker_WorkerIdAndStatusInOrderByScheduledAtAsc(eq(workerId), any()))
                .thenReturn(List.of());
        assertThatCode(() -> guard.ensureWorkerFree(workerId, target)).doesNotThrowAnyException();
    }

    @Test
    void a_worker_with_another_in_flight_job_is_rejected_with_a_clear_message() {
        Booking target = booking(BookingType.INSTANT, BookingStage.SEARCHING);
        Booking other = booking(BookingType.INSTANT, BookingStage.IN_PROGRESS);
        when(bookingRepository.findByWorker_WorkerIdAndStatusInOrderByScheduledAtAsc(eq(workerId), any()))
                .thenReturn(List.of(other));
        assertThatThrownBy(() -> guard.ensureWorkerFree(workerId, target))
                .isInstanceOf(WorkerBusyException.class)
                .hasMessageContaining("already have an active job");
    }

    @Test
    void the_booking_being_accepted_does_not_count_against_the_worker() {
        Booking target = booking(BookingType.INSTANT, BookingStage.ACCEPTED);
        when(bookingRepository.findByWorker_WorkerIdAndStatusInOrderByScheduledAtAsc(eq(workerId), any()))
                .thenReturn(List.of(target)); // e.g. an idempotent re-accept of the same booking
        assertThatCode(() -> guard.ensureWorkerFree(workerId, target)).doesNotThrowAnyException();
    }

    @Test
    void the_worker_lock_is_taken_BEFORE_the_busy_check_so_the_second_accept_sees_the_first_commit() {
        Booking target = booking(BookingType.INSTANT, BookingStage.SEARCHING);
        when(bookingRepository.findByWorker_WorkerIdAndStatusInOrderByScheduledAtAsc(eq(workerId), any()))
                .thenReturn(List.of());
        guard.ensureWorkerFree(workerId, target);

        InOrder order = inOrder(workerRepository, bookingRepository);
        order.verify(workerRepository).lockWorkerAcceptance("worker_accept:" + workerId);
        order.verify(bookingRepository).findByWorker_WorkerIdAndStatusInOrderByScheduledAtAsc(eq(workerId), any());
    }

    @Test
    void the_in_flight_stages_match_dispatch_busy_rule_minus_future_confirmed_slots() {
        assertThat(WorkerAcceptanceGuard.IN_FLIGHT_STAGES).containsExactlyInAnyOrder(
                BookingStage.ACCEPTED, BookingStage.ON_THE_WAY, BookingStage.ARRIVED, BookingStage.IN_PROGRESS);
        assertThat(WorkerAcceptanceGuard.IN_FLIGHT_STAGES).doesNotContain(BookingStage.CONFIRMED);
    }

    @Test
    void scheduled_bookings_are_not_subject_to_the_guard() {
        Booking scheduled = booking(BookingType.SCHEDULED, BookingStage.PENDING);
        guard.ensureWorkerFree(workerId, scheduled);
        verifyNoInteractions(workerRepository, bookingRepository);
    }

    @Test
    void the_kill_switch_restores_previous_behaviour() {
        WorkerAcceptanceGuard off = new WorkerAcceptanceGuard(workerRepository, bookingRepository, false);
        off.ensureWorkerFree(workerId, booking(BookingType.INSTANT, BookingStage.SEARCHING));
        verifyNoInteractions(workerRepository, bookingRepository);
    }

    @Test
    void null_inputs_are_ignored_rather_than_throwing() {
        assertThatCode(() -> guard.ensureWorkerFree(null, booking(BookingType.INSTANT, BookingStage.SEARCHING)))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.ensureWorkerFree(workerId, null)).doesNotThrowAnyException();
    }

    /**
     * Simulation of the race: both accepts for the same worker run on separate threads; the "lock" is modelled by a
     * monitor and the "commit" by writing the winner's booking into a shared list BEFORE releasing it, which is what
     * a transaction-scoped advisory lock does. Exactly one must win. (Models the protocol, not Postgres.)
     */
    @Test
    void two_simultaneous_accepts_by_one_worker_yield_exactly_one_winner() throws Exception {
        Object workerLock = new Object();
        List<Booking> committed = new java.util.concurrent.CopyOnWriteArrayList<>();
        when(workerRepository.lockWorkerAcceptance(anyString())).thenReturn(null);
        when(bookingRepository.findByWorker_WorkerIdAndStatusInOrderByScheduledAtAsc(eq(workerId), any()))
                .thenAnswer(inv -> List.copyOf(committed));

        AtomicInteger wins = new AtomicInteger();
        AtomicInteger busy = new AtomicInteger();
        Runnable accept = () -> {
            Booking mine = booking(BookingType.INSTANT, BookingStage.SEARCHING);
            synchronized (workerLock) {                // advisory lock acquired (and held until "commit")
                try {
                    guard.ensureWorkerFree(workerId, mine);
                    mine.setStatus(BookingStage.ACCEPTED);
                    committed.add(mine);               // commit happens before the lock is released
                    wins.incrementAndGet();
                } catch (WorkerBusyException e) {
                    busy.incrementAndGet();
                }
            }
        };
        Thread a = new Thread(accept);
        Thread b = new Thread(accept);
        a.start(); b.start(); a.join(); b.join();

        assertThat(wins.get()).isEqualTo(1);
        assertThat(busy.get()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- both accept paths call the guard first

    @Test
    @SuppressWarnings("unchecked")
    void BookingAssignmentService_rejects_before_it_writes_anything_when_the_guard_says_busy() {
        BookingRepository bookings = mock(BookingRepository.class);
        WorkerRepository workers = mock(WorkerRepository.class);
        BookingAssignmentService svc = new BookingAssignmentService(workers, mock(WorkerServiceLinkRepository.class),
                bookings, mock(BookingAssignmentRepository.class), mock(ReviewRepository.class),
                mock(NotificationDeliveryRepository.class), mock(RedisTemplate.class),
                mock(FcmNotificationService.class), mock(BookingDomainEventService.class),
                mock(org.springframework.messaging.simp.SimpMessagingTemplate.class), mock(BookingOtpService.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), mock(DispatchAuditLogRepository.class));
        WorkerAcceptanceGuard busyGuard = mock(WorkerAcceptanceGuard.class);
        svc.setAcceptanceGuard(busyGuard);

        UUID bookingId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Booking b = Booking.builder().bookingId(bookingId).bookingType(BookingType.INSTANT)
                .status(BookingStage.SEARCHING).build();
        Worker w = Worker.builder().workerId(workerId).user(User.builder().userId(userId).build()).build();
        when(bookings.findByIdForUpdate(bookingId)).thenReturn(Optional.of(b));
        when(workers.findByUser_UserId(userId)).thenReturn(Optional.of(w));
        doThrow(new WorkerBusyException("busy")).when(busyGuard).ensureWorkerFree(workerId, b);

        assertThatThrownBy(() -> svc.handleWorkerAcceptance(bookingId, userId)).isInstanceOf(WorkerBusyException.class);
        verify(bookings, never()).save(any(Booking.class));
        assertThat(b.getStatus()).isEqualTo(BookingStage.SEARCHING);
        assertThat(b.getWorker()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void InstantAcceptService_rejects_before_it_writes_anything_when_the_guard_says_busy() {
        BookingRepository bookings = mock(BookingRepository.class);
        WorkerRepository workers = mock(WorkerRepository.class);
        jakarta.persistence.EntityManager em = mock(jakarta.persistence.EntityManager.class);
        jakarta.persistence.Query q = mock(jakarta.persistence.Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(q);
        when(q.setParameter(anyString(), any())).thenReturn(q);
        InstantAcceptService svc = new InstantAcceptService(bookings, workers, mock(BookingAssignmentRepository.class),
                mock(BookingDomainEventService.class),
                mock(org.springframework.messaging.simp.SimpMessagingTemplate.class),
                mock(FcmNotificationService.class), mock(RedisTemplate.class),
                new com.fasterxml.jackson.databind.ObjectMapper());
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "entityManager", em);
        WorkerAcceptanceGuard busyGuard = mock(WorkerAcceptanceGuard.class);
        svc.setAcceptanceGuard(busyGuard);

        UUID bookingId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Booking b = Booking.builder().bookingId(bookingId).bookingType(BookingType.INSTANT)
                .status(BookingStage.SEARCHING).build();
        Worker w = Worker.builder().workerId(workerId).user(User.builder().userId(userId).build()).build();
        when(bookings.findByIdForUpdate(bookingId)).thenReturn(Optional.of(b));
        when(workers.findByUser_UserId(userId)).thenReturn(Optional.of(w));
        doThrow(new WorkerBusyException("busy")).when(busyGuard).ensureWorkerFree(workerId, b);

        assertThatThrownBy(() -> svc.acceptBooking(bookingId, userId)).isInstanceOf(WorkerBusyException.class);
        verify(bookings, never()).save(any(Booking.class));
        assertThat(b.getStatus()).isEqualTo(BookingStage.SEARCHING);
    }
}
