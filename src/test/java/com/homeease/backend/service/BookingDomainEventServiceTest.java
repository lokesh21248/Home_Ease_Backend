package com.homeease.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.homeease.backend.dto.BookingEventDto.BookingEventEnvelope;
import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.*;
import com.homeease.backend.repository.BookingEventHistoryRepository;
import com.homeease.backend.repository.BookingEventOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingDomainEventServiceTest {

    @Mock
    private BookingEventHistoryRepository historyRepository;

    @Mock
    private BookingEventOutboxRepository outboxRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @InjectMocks
    private BookingDomainEventService domainEventService;

    private Booking sampleBooking;
    private UUID bookingId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        bookingId = UUID.randomUUID();
        userId = UUID.randomUUID();

        ServiceEntity service = ServiceEntity.builder()
                .serviceId(UUID.randomUUID())
                .name("Deep Cleaning")
                .build();

        sampleBooking = Booking.builder()
                .bookingId(bookingId)
                .user(User.builder().userId(userId).fullName("Test User").phoneNumber("9876543210").build())
                .service(service)
                .status(BookingStage.SEARCHING)
                .servicePincode("500072")
                .paymentStatus(TransactionState.PENDING)
                .paymentMethod(PaymentMode.UPI)
                .baseAmount(new BigDecimal("599.00"))
                .totalAmount(new BigDecimal("599.00"))
                .createdAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("recordEvent persists authoritative history and outbox in same transaction")
    void testRecordEvent_Success() throws Exception {
        BookingEventEnvelope envelope = domainEventService.recordEvent(
                sampleBooking,
                BookingEventType.BOOKING_CREATED,
                null,
                BookingStage.SEARCHING,
                BookingActorType.CUSTOMER,
                userId,
                "corr-123",
                null,
                null
        );

        assertNotNull(envelope);
        assertNotNull(envelope.getEventId());
        assertEquals(BookingEventType.BOOKING_CREATED, envelope.getEventType());
        assertEquals(1, envelope.getSchemaVersion());
        assertEquals(bookingId, envelope.getBookingId());
        assertEquals(BookingStage.SEARCHING, envelope.getNewStatus());
        assertEquals("500072", envelope.getServicePincode());
        assertEquals("corr-123", envelope.getCorrelationId());

        // Verify history entity saved
        ArgumentCaptor<BookingEventHistory> historyCaptor = ArgumentCaptor.forClass(BookingEventHistory.class);
        verify(historyRepository, times(1)).save(historyCaptor.capture());
        BookingEventHistory savedHistory = historyCaptor.getValue();
        assertEquals(envelope.getEventId(), savedHistory.getEventId());
        assertEquals(bookingId, savedHistory.getBookingId());
        assertEquals(BookingEventType.BOOKING_CREATED, savedHistory.getEventType());
        assertEquals(BookingActorType.CUSTOMER, savedHistory.getActorType());
        assertEquals(userId, savedHistory.getActorId());

        // Verify outbox entity saved
        ArgumentCaptor<BookingEventOutbox> outboxCaptor = ArgumentCaptor.forClass(BookingEventOutbox.class);
        verify(outboxRepository, times(1)).save(outboxCaptor.capture());
        BookingEventOutbox savedOutbox = outboxCaptor.getValue();
        assertEquals(envelope.getEventId(), savedOutbox.getEventId());
        assertEquals(bookingId, savedOutbox.getAggregateId());
        assertEquals(OutboxStatus.PENDING, savedOutbox.getStatus());
        assertEquals(0, savedOutbox.getAttemptCount());

        // Verify serialized JSON payload matches envelope
        BookingEventEnvelope deserialized = objectMapper.readValue(savedOutbox.getPayload(), BookingEventEnvelope.class);
        assertEquals(envelope.getEventId(), deserialized.getEventId());
        assertEquals(envelope.getBookingId(), deserialized.getBookingId());
    }

    @Test
    @DisplayName("recordEvent preserves privacy: does not include phone number or user full name")
    void testRecordEvent_PrivacyEnforced() {
        BookingEventEnvelope envelope = domainEventService.recordEvent(
                sampleBooking,
                BookingEventType.BOOKING_CREATED,
                null,
                BookingStage.SEARCHING,
                BookingActorType.CUSTOMER,
                userId
        );

        String json = envelope.toString();
        assertFalse(json.contains("9876543210"), "Phone number must not leak into event envelope");
        assertFalse(json.contains("Test User"), "User full name must not leak into event envelope");
    }
}
