package com.homeease.backend.service;

import com.homeease.backend.dto.BookingDto.BookingResponse;
import com.homeease.backend.model.entity.Booking;
import com.homeease.backend.model.entity.User;
import com.homeease.backend.model.entity.Worker;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.UserRole;
import com.homeease.backend.repository.BookingRepository;
import com.homeease.backend.repository.BookingSlotRepository;
import com.homeease.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class BookingCancellationServiceUnitTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private BookingSlotRepository slotRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private BookingNotificationService notificationService;
    @Mock
    private BookingDomainEventService domainEventService;
    @Mock
    private RedisTemplate<String, String> redisTemplate;
    @Mock
    private TrackingService trackingService;

    private BookingCancellationService cancellationService;

    private UUID bookingId;
    private UUID customerId;
    private UUID workerUserId;
    private User customerUser;
    private User workerUser;
    private Worker worker;
    private Booking booking;

    @BeforeEach
    void setUp() {
        cancellationService = new BookingCancellationService(
                bookingRepository,
                slotRepository,
                userRepository,
                notificationService,
                domainEventService,
                redisTemplate,
                trackingService
        );

        bookingId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        workerUserId = UUID.randomUUID();

        customerUser = new User();
        customerUser.setUserId(customerId);
        customerUser.setRole(UserRole.CUSTOMER);

        workerUser = new User();
        workerUser.setUserId(workerUserId);
        workerUser.setRole(UserRole.WORKER);

        worker = new Worker();
        worker.setWorkerId(UUID.randomUUID());
        worker.setUser(workerUser);

        booking = new Booking();
        booking.setBookingId(bookingId);
        booking.setUser(customerUser);
        booking.setWorker(worker);
        booking.setStatus(BookingStage.ACCEPTED);
    }

    @Test
    @DisplayName("Should cancel successfully when booking is in valid cancelable state (ACCEPTED)")
    void testCancelBooking_Success() {
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(customerId)).thenReturn(Optional.of(customerUser));
        when(bookingRepository.directCancelBooking(eq(bookingId), anyString(), eq(customerUser), any(Instant.class)))
                .thenReturn(1);

        BookingResponse response = cancellationService.cancelBooking(bookingId, customerId, "Need to reschedule");

        assertNotNull(response);
        verify(bookingRepository).directCancelBooking(eq(bookingId), anyString(), eq(customerUser), any(Instant.class));
        verify(trackingService).stopTracking(bookingId, "CANCELLED");
    }

    @Test
    @DisplayName("Should reject cancellation when booking is already COMPLETED")
    void testCancelBooking_AlreadyCompleted_ThrowsException() {
        booking.setStatus(BookingStage.COMPLETED);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                cancellationService.cancelBooking(bookingId, customerId, "Cancel test"));

        assertTrue(ex.getMessage().contains("Completed bookings cannot be cancelled"));
        verify(bookingRepository, never()).directCancelBooking(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should reject cancellation when service is IN_PROGRESS")
    void testCancelBooking_InProgress_ThrowsException() {
        booking.setStatus(BookingStage.IN_PROGRESS);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                cancellationService.cancelBooking(bookingId, customerId, "Cancel test"));

        assertTrue(ex.getMessage().contains("already in progress"));
        verify(bookingRepository, never()).directCancelBooking(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should be idempotent when booking is already CANCELLED")
    void testCancelBooking_AlreadyCancelled_Idempotent() {
        booking.setStatus(BookingStage.CANCELLED);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));

        BookingResponse response = cancellationService.cancelBooking(bookingId, customerId, "Duplicate cancel");

        assertNotNull(response);
        assertEquals(BookingStage.CANCELLED, response.getStatus());
        verify(bookingRepository, never()).directCancelBooking(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Race condition guard: If concurrent thread completed the booking during cancel, throw IllegalStateException")
    void testCancelBooking_ConcurrentCompletionRace_Guarded() {
        // Initial read saw ACCEPTED
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(customerId)).thenReturn(Optional.of(customerUser));

        // Direct update returned 0 because atomic state guard prevented overwriting completed booking
        when(bookingRepository.directCancelBooking(eq(bookingId), anyString(), eq(customerUser), any(Instant.class)))
                .thenReturn(0);

        // When re-checked, booking was completed concurrently
        Booking completedBooking = new Booking();
        completedBooking.setBookingId(bookingId);
        completedBooking.setUser(customerUser);
        completedBooking.setWorker(worker);
        completedBooking.setStatus(BookingStage.COMPLETED);

        // Subsequent findById returns the concurrently completed booking
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking), Optional.of(completedBooking));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                cancellationService.cancelBooking(bookingId, customerId, "Race cancel"));

        assertTrue(ex.getMessage().contains("Completed bookings cannot be cancelled"));
        verify(bookingRepository, never()).saveAndFlush(any());
    }
}
