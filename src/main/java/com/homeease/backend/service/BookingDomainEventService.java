package com.homeease.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.dto.BookingEventDto.BookingEventEnvelope;
import com.homeease.backend.model.entity.Booking;
import com.homeease.backend.model.entity.BookingEventHistory;
import com.homeease.backend.model.entity.BookingEventOutbox;
import com.homeease.backend.model.enums.BookingActorType;
import com.homeease.backend.model.enums.BookingEventType;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.OutboxStatus;
import com.homeease.backend.repository.BookingEventHistoryRepository;
import com.homeease.backend.repository.BookingEventOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class BookingDomainEventService {

    private static final Logger logger = LoggerFactory.getLogger(BookingDomainEventService.class);

    private final BookingEventHistoryRepository historyRepository;
    private final BookingEventOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public BookingDomainEventService(BookingEventHistoryRepository historyRepository,
                                     BookingEventOutboxRepository outboxRepository,
                                     ObjectMapper objectMapper) {
        this.historyRepository = historyRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Atomically records an authoritative booking event history entry and an outbox record
     * within the caller's active database transaction (MANDATORY propagation).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BookingEventEnvelope recordEvent(Booking booking,
                                            BookingEventType eventType,
                                            BookingStage previousStatus,
                                            BookingStage newStatus,
                                            BookingActorType actorType,
                                            UUID actorId) {
        return recordEvent(booking, eventType, previousStatus, newStatus, actorType, actorId, null, null, null);
    }

    /**
     * Full recording method with correlation ID and extra sub-service line items.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BookingEventEnvelope recordEvent(Booking booking,
                                            BookingEventType eventType,
                                            BookingStage previousStatus,
                                            BookingStage newStatus,
                                            BookingActorType actorType,
                                            UUID actorId,
                                            String correlationId,
                                            UUID extraSubServiceId,
                                            Integer extraQuantity) {

        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();

        // 1. Build versioned DTO envelope (sanitized, privacy-safe)
        BookingEventEnvelope envelope = BookingEventEnvelope.builder()
                .eventId(eventId)
                .eventType(eventType)
                .schemaVersion(1)
                .occurredAt(now)
                .bookingId(booking.getBookingId())
                .previousStatus(previousStatus)
                .newStatus(newStatus)
                .actorType(actorType != null ? actorType : BookingActorType.SYSTEM)
                .actorId(actorId)
                .correlationId(correlationId)
                .serviceId(booking.getService() != null ? booking.getService().getServiceId() : null)
                .servicePincode(booking.getServicePincode())
                .paymentStatus(booking.getPaymentStatus())
                .paymentMethod(booking.getPaymentMethod())
                .extraSubServiceId(extraSubServiceId)
                .extraQuantity(extraQuantity)
                .build();

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize booking event envelope to JSON for event " + eventId, e);
        }

        // 2. Persist authoritative history audit entry (PostgreSQL source of truth)
        BookingEventHistory history = BookingEventHistory.builder()
                .eventId(eventId)
                .bookingId(booking.getBookingId())
                .eventType(eventType)
                .schemaVersion(1)
                .previousStatus(previousStatus)
                .newStatus(newStatus)
                .actorType(actorType != null ? actorType : BookingActorType.SYSTEM)
                .actorId(actorId)
                .correlationId(correlationId)
                .occurredAt(now)
                .build();
        historyRepository.save(history);

        // 3. Persist transactional outbox entry for reliable asynchronous Kafka publishing
        BookingEventOutbox outbox = BookingEventOutbox.builder()
                .eventId(eventId)
                .eventType(eventType)
                .aggregateId(booking.getBookingId())
                .schemaVersion(1)
                .payload(payloadJson)
                .status(OutboxStatus.PENDING)
                .attemptCount(0)
                .nextAttemptAt(now)
                .build();
        outboxRepository.save(outbox);

        logger.info("Recorded authoritative booking event: type={}, eventId={}, bookingId={}, status={}",
                eventType, eventId, booking.getBookingId(), newStatus);

        return envelope;
    }
}
