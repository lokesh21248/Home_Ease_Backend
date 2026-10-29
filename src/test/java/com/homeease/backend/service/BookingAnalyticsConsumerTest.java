package com.homeease.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.homeease.backend.dto.BookingEventDto.BookingEventEnvelope;
import com.homeease.backend.model.entity.BookingAnalyticsDaily;
import com.homeease.backend.model.entity.BookingAnalyticsFacts;
import com.homeease.backend.model.entity.BookingAnalyticsInbox;
import com.homeease.backend.model.enums.BookingActorType;
import com.homeease.backend.model.enums.BookingEventType;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.TransactionState;
import com.homeease.backend.repository.BookingAnalyticsDailyRepository;
import com.homeease.backend.repository.BookingAnalyticsFactsRepository;
import com.homeease.backend.repository.BookingAnalyticsInboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingAnalyticsConsumerTest {

    @Mock
    private BookingAnalyticsInboxRepository inboxRepository;

    @Mock
    private BookingAnalyticsFactsRepository factsRepository;

    @Mock
    private BookingAnalyticsDailyRepository dailyRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @InjectMocks
    private BookingAnalyticsConsumer analyticsConsumer;

    private UUID eventId;
    private UUID bookingId;
    private Instant now;

    @BeforeEach
    void setUp() {
        eventId = UUID.randomUUID();
        bookingId = UUID.randomUUID();
        now = Instant.now();
    }

    @Test
    @DisplayName("consumeBookingEvent processes event and updates inbox, facts, and daily aggregates")
    void testConsumeBookingEvent_FirstTime_Success() throws Exception {
        BookingEventEnvelope envelope = BookingEventEnvelope.builder()
                .eventId(eventId)
                .eventType(BookingEventType.BOOKING_CREATED)
                .bookingId(bookingId)
                .newStatus(BookingStage.SEARCHING)
                .servicePincode("500072")
                .occurredAt(now)
                .actorType(BookingActorType.CUSTOMER)
                .paymentStatus(TransactionState.PENDING)
                .build();

        String json = objectMapper.writeValueAsString(envelope);

        when(inboxRepository.existsById(eventId)).thenReturn(false);
        when(factsRepository.findById(bookingId)).thenReturn(Optional.empty());
        when(dailyRepository.findById(any())).thenReturn(Optional.empty());

        analyticsConsumer.consumeBookingEvent(json);

        // Verify inbox recorded
        verify(inboxRepository, times(1)).save(any(BookingAnalyticsInbox.class));

        // Verify facts updated
        ArgumentCaptor<BookingAnalyticsFacts> factsCaptor = ArgumentCaptor.forClass(BookingAnalyticsFacts.class);
        verify(factsRepository, times(1)).save(factsCaptor.capture());
        BookingAnalyticsFacts savedFacts = factsCaptor.getValue();
        assertEquals(bookingId, savedFacts.getBookingId());
        assertEquals(BookingStage.SEARCHING, savedFacts.getCurrentStatus());
        assertEquals("500072", savedFacts.getServicePincode());

        // Verify daily aggregates incremented
        ArgumentCaptor<BookingAnalyticsDaily> dailyCaptor = ArgumentCaptor.forClass(BookingAnalyticsDaily.class);
        verify(dailyRepository, times(1)).save(dailyCaptor.capture());
        BookingAnalyticsDaily savedDaily = dailyCaptor.getValue();
        assertEquals(1L, savedDaily.getCreatedCount());
    }

    @Test
    @DisplayName("consumeBookingEvent deduplicates duplicate delivery: skips processing if eventId exists in inbox")
    void testConsumeBookingEvent_DuplicateDelivery_Deduplicated() throws Exception {
        BookingEventEnvelope envelope = BookingEventEnvelope.builder()
                .eventId(eventId)
                .eventType(BookingEventType.BOOKING_CREATED)
                .bookingId(bookingId)
                .occurredAt(now)
                .build();

        String json = objectMapper.writeValueAsString(envelope);

        // Already exists in inbox
        when(inboxRepository.existsById(eventId)).thenReturn(true);

        analyticsConsumer.consumeBookingEvent(json);

        // Verify no updates performed
        verify(inboxRepository, never()).save(any());
        verify(factsRepository, never()).save(any());
        verify(dailyRepository, never()).save(any());
    }

    @Test
    @DisplayName("consumeBookingEvent computes latency for PARTNER_ACCEPTED")
    void testConsumeBookingEvent_LatencyComputation() throws Exception {
        Instant createdTime = now.minus(5, ChronoUnit.MINUTES);
        Instant acceptedTime = now;

        BookingAnalyticsFacts existingFacts = BookingAnalyticsFacts.builder()
                .bookingId(bookingId)
                .createdAt(createdTime)
                .servicePincode("500072")
                .build();

        BookingEventEnvelope envelope = BookingEventEnvelope.builder()
                .eventId(eventId)
                .eventType(BookingEventType.PARTNER_ACCEPTED)
                .bookingId(bookingId)
                .newStatus(BookingStage.ACCEPTED)
                .servicePincode("500072")
                .occurredAt(acceptedTime)
                .actorType(BookingActorType.WORKER)
                .build();

        String json = objectMapper.writeValueAsString(envelope);

        when(inboxRepository.existsById(eventId)).thenReturn(false);
        when(factsRepository.findById(bookingId)).thenReturn(Optional.of(existingFacts));
        when(dailyRepository.findById(any())).thenReturn(Optional.empty());

        analyticsConsumer.consumeBookingEvent(json);

        ArgumentCaptor<BookingAnalyticsFacts> factsCaptor = ArgumentCaptor.forClass(BookingAnalyticsFacts.class);
        verify(factsRepository, times(1)).save(factsCaptor.capture());
        BookingAnalyticsFacts savedFacts = factsCaptor.getValue();

        assertEquals(acceptedTime, savedFacts.getAcceptedAt());
        assertNotNull(savedFacts.getCreateToAcceptMs());
        assertEquals(300000L, savedFacts.getCreateToAcceptMs(), 1000L); // ~300,000 ms
    }
}
