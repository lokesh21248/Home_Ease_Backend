package com.homeease.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.model.entity.Booking;
import com.homeease.backend.model.entity.BookingAssignment;
import com.homeease.backend.model.entity.ServiceEntity;
import com.homeease.backend.model.entity.User;
import com.homeease.backend.model.entity.Worker;
import com.homeease.backend.model.enums.AssignmentStatus;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.BookingType;
import com.homeease.backend.repository.BookingAssignmentRepository;
import com.homeease.backend.repository.BookingRepository;
import com.homeease.backend.repository.DispatchAuditLogRepository;
import com.homeease.backend.repository.NotificationDeliveryRepository;
import com.homeease.backend.repository.ReviewRepository;
import com.homeease.backend.repository.WorkerRepository;
import com.homeease.backend.repository.WorkerServiceLinkRepository;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * {@code dispatchInstantBookingById} is called from five places (request thread, recovery job, Kafka consumer, retry
 * endpoint, reassignment). Each may arrive late or twice, so the method itself must refuse anything that is not
 * waiting for a worker. These tests pin that rule down without a database.
 */
class DispatchByIdGuardTest {

    private BookingRepository bookingRepository;
    private BookingAssignmentRepository assignmentRepository;
    private WorkerRepository workerRepository;
    private BookingAssignmentService service;

    private UUID bookingId;
    private Booking booking;

    @BeforeEach
    void setUp() {
        bookingRepository = mock(BookingRepository.class);
        assignmentRepository = mock(BookingAssignmentRepository.class);
        workerRepository = mock(WorkerRepository.class);
        @SuppressWarnings("unchecked")
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        service = new BookingAssignmentService(
                workerRepository, mock(WorkerServiceLinkRepository.class), bookingRepository, assignmentRepository,
                mock(ReviewRepository.class), mock(NotificationDeliveryRepository.class), redisTemplate,
                mock(FcmNotificationService.class), mock(BookingDomainEventService.class),
                mock(SimpMessagingTemplate.class), mock(BookingOtpService.class), new ObjectMapper(),
                mock(DispatchAuditLogRepository.class));

        bookingId = UUID.randomUUID();
        booking = Booking.builder()
                .bookingId(bookingId)
                .user(User.builder().userId(UUID.randomUUID()).build())
                .service(ServiceEntity.builder().serviceId(UUID.randomUUID()).name("Electrician").build())
                .bookingType(BookingType.INSTANT)
                .status(BookingStage.REQUESTED)
                .userLat(17.4486)
                .userLng(78.3908)
                .servicePincode("500081")
                .build();
        when(bookingRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(booking));
        when(workerRepository.findByIsOnlineTrueAndIsVerifiedTrue()).thenReturn(Collections.emptyList());
    }

    private void assertNothingWasDispatched() {
        verify(workerRepository, never()).findByIsOnlineTrueAndIsVerifiedTrue();
        verify(bookingRepository, never()).save(any(Booking.class));
        verify(assignmentRepository, never()).save(any(BookingAssignment.class));
    }

    @ParameterizedTest(name = "a booking that is {0} is left completely alone")
    @EnumSource(value = BookingStage.class,
            names = {"ACCEPTED", "CONFIRMED", "ON_THE_WAY", "ARRIVED", "IN_PROGRESS", "COMPLETED",
                    "CANCELLED", "REJECTED", "EXPIRED", "WORKER_NOT_FOUND"})
    @DisplayName("late or duplicate dispatch for a booking that is not waiting for a worker is a no-op")
    void bookingsThatAreNotWaitingForAWorker_areNeverDispatched(BookingStage status) {
        booking.setStatus(status);

        service.dispatchInstantBookingById(bookingId, UUID.randomUUID());

        assertNothingWasDispatched();
        assertThat(booking.getStatus()).as("status must not be overwritten (e.g. ACCEPTED -> WORKER_NOT_FOUND)").isEqualTo(status);
    }

    @Test
    @DisplayName("a booking that does not exist is skipped without an error")
    void unknownBooking_isSkipped() {
        UUID unknown = UUID.randomUUID();
        when(bookingRepository.findByIdForUpdate(unknown)).thenReturn(Optional.empty());

        service.dispatchInstantBookingById(unknown, null);
        service.dispatchInstantBookingById(null, null);

        assertNothingWasDispatched();
    }

    @ParameterizedTest(name = "a {0} booking is dispatched")
    @EnumSource(value = BookingStage.class, names = {"REQUESTED", "PENDING"})
    @DisplayName("a booking that is waiting for a worker is dispatched")
    void waitingBookings_areDispatched(BookingStage status) {
        booking.setStatus(status);

        service.dispatchInstantBookingById(bookingId, UUID.randomUUID());

        verify(workerRepository, org.mockito.Mockito.atLeastOnce()).findByIsOnlineTrueAndIsVerifiedTrue();
        assertThat(booking.getStatus()).as("nobody is available, so the outcome is recorded").isEqualTo(BookingStage.WORKER_NOT_FOUND);
        verify(bookingRepository).save(booking);
    }

    @Test
    @DisplayName("SEARCHING with an offer still outstanding is in flight: skipped; SEARCHING with nobody holding an offer is dispatched again")
    void searchingBooking_isDispatchedOnlyWhenNobodyHoldsAnOffer() {
        booking.setStatus(BookingStage.SEARCHING);
        when(assignmentRepository.findByBooking_BookingIdAndStatus(bookingId, AssignmentStatus.PENDING))
                .thenReturn(List.of(BookingAssignment.builder().status(AssignmentStatus.PENDING).build()));

        service.dispatchInstantBookingById(bookingId, null);
        assertNothingWasDispatched();

        when(assignmentRepository.findByBooking_BookingIdAndStatus(bookingId, AssignmentStatus.PENDING))
                .thenReturn(Collections.emptyList());
        service.dispatchInstantBookingById(bookingId, null);
        verify(workerRepository, org.mockito.Mockito.atLeastOnce()).findByIsOnlineTrueAndIsVerifiedTrue();
    }

    @Test
    @DisplayName("SEARCHING but already holding a worker is never dispatched")
    void searchingBookingWithAWorker_isNeverDispatched() {
        booking.setStatus(BookingStage.SEARCHING);
        booking.setWorker(Worker.builder().workerId(UUID.randomUUID()).build());

        service.dispatchInstantBookingById(bookingId, null);

        assertNothingWasDispatched();
    }

    /** What POST /bookings/{id}/retry-dispatch does before it dispatches: put the booking back to REQUESTED. */
    private BookingAssignment earlierOffer(Instant expiresAt) {
        Worker holder = Worker.builder().workerId(UUID.randomUUID()).build();
        BookingAssignment offer = BookingAssignment.builder()
                .booking(booking).worker(holder).status(AssignmentStatus.PENDING).expiresAt(expiresAt).build();
        when(assignmentRepository.findByBooking_BookingId(bookingId)).thenReturn(List.of(offer));
        when(assignmentRepository.findByBooking_BookingIdAndStatus(bookingId, AssignmentStatus.PENDING)).thenReturn(List.of(offer));
        return offer;
    }

    @Test
    @DisplayName("retry with an earlier offer still alive and nobody new to ask: the search goes on, the customer is NOT told nobody was found")
    void retryWithALiveOfferAndNobodyNew_keepsSearching() {
        earlierOffer(Instant.now().plusSeconds(120));
        booking.setStatus(BookingStage.REQUESTED);

        service.dispatchInstantBookingById(bookingId, null);

        assertThat(booking.getStatus())
                .as("a worker still holds a live offer: the booking is not exhausted, and that worker must still be able to accept")
                .isEqualTo(BookingStage.SEARCHING);
        verify(assignmentRepository, never()).save(any(BookingAssignment.class));
    }

    @Test
    @DisplayName("retry when the earlier offer has already expired and nobody new is available: nobody is looking, so WORKER_NOT_FOUND")
    void retryWithOnlyAnExpiredOfferAndNobodyNew_recordsWorkerNotFound() {
        earlierOffer(Instant.now().minusSeconds(5));
        booking.setStatus(BookingStage.REQUESTED);

        service.dispatchInstantBookingById(bookingId, null);

        assertThat(booking.getStatus()).isEqualTo(BookingStage.WORKER_NOT_FOUND);
    }
}
