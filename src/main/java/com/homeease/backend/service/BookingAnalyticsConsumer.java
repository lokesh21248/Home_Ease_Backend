package com.homeease.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.dto.BookingEventDto.BookingEventEnvelope;
import com.homeease.backend.model.entity.BookingAnalyticsDaily;
import com.homeease.backend.model.entity.BookingAnalyticsFacts;
import com.homeease.backend.model.entity.BookingAnalyticsInbox;
import com.homeease.backend.model.enums.TransactionState;
import com.homeease.backend.repository.BookingAnalyticsDailyRepository;
import com.homeease.backend.repository.BookingAnalyticsFactsRepository;
import com.homeease.backend.repository.BookingAnalyticsInboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

@Service
public class BookingAnalyticsConsumer {

    private static final Logger logger = LoggerFactory.getLogger(BookingAnalyticsConsumer.class);

    private final BookingAnalyticsInboxRepository inboxRepository;
    private final BookingAnalyticsFactsRepository factsRepository;
    private final BookingAnalyticsDailyRepository dailyRepository;
    private final ObjectMapper objectMapper;

    public BookingAnalyticsConsumer(BookingAnalyticsInboxRepository inboxRepository,
                                    BookingAnalyticsFactsRepository factsRepository,
                                    BookingAnalyticsDailyRepository dailyRepository,
                                    ObjectMapper objectMapper) {
        this.inboxRepository = inboxRepository;
        this.factsRepository = factsRepository;
        this.dailyRepository = dailyRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Independent consumer group for asynchronous booking analytics.
     * Uses the transactional inbox pattern to guarantee exactly-once processing semantics.
     */
    @KafkaListener(
            topics = "${homeease.kafka.topics.booking-events:firstdoor.booking.events.v1}",
            groupId = "firstdoor-booking-analytics",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void consumeBookingEvent(String messagePayload) {
        BookingEventEnvelope event;
        try {
            event = objectMapper.readValue(messagePayload, BookingEventEnvelope.class);
        } catch (Exception e) {
            logger.error("Failed to parse booking event payload for analytics consumer: {}", e.getMessage());
            throw new IllegalArgumentException("Corrupt event envelope payload", e);
        }

        if (event == null || event.getEventId() == null || event.getBookingId() == null) {
            logger.warn("Skipping invalid event without eventId or bookingId: {}", messagePayload);
            return;
        }

        // 1. Idempotency guard — inbox pattern prevents duplicate metric counting on redelivery
        if (inboxRepository.existsById(event.getEventId())) {
            logger.info("Event {} already processed by analytics consumer. Skipping deduplicated event.", event.getEventId());
            return;
        }

        // Record event in inbox within this transaction
        inboxRepository.save(new BookingAnalyticsInbox(event.getEventId(), Instant.now()));

        // 2. Update granular booking milestone facts
        updateBookingFacts(event);

        // 3. Update aggregated daily metrics
        updateDailyAggregates(event);

        logger.info("Successfully processed analytics for eventId={}, type={}, bookingId={}",
                event.getEventId(), event.getEventType(), event.getBookingId());
    }

    private void updateBookingFacts(BookingEventEnvelope event) {
        BookingAnalyticsFacts facts = factsRepository.findById(event.getBookingId())
                .orElseGet(() -> BookingAnalyticsFacts.builder()
                        .bookingId(event.getBookingId())
                        .build());

        if (event.getServiceId() != null) {
            facts.setServiceId(event.getServiceId());
        }
        if (event.getServicePincode() != null && !event.getServicePincode().isBlank()) {
            facts.setServicePincode(event.getServicePincode());
        }
        if (event.getNewStatus() != null) {
            facts.setCurrentStatus(event.getNewStatus());
        }
        if (event.getPaymentStatus() != null) {
            facts.setPaymentStatus(event.getPaymentStatus());
        }

        switch (event.getEventType()) {
            case BOOKING_CREATED -> {
                if (facts.getCreatedAt() == null) {
                    facts.setCreatedAt(event.getOccurredAt());
                }
            }
            case PARTNER_ACCEPTED -> {
                if (facts.getAcceptedAt() == null) {
                    facts.setAcceptedAt(event.getOccurredAt());
                    if (facts.getCreatedAt() != null) {
                        long latency = ChronoUnit.MILLIS.between(facts.getCreatedAt(), event.getOccurredAt());
                        facts.setCreateToAcceptMs(Math.max(0L, latency));
                    }
                }
            }
            case JOB_STARTED -> {
                if (facts.getStartedAt() == null) {
                    facts.setStartedAt(event.getOccurredAt());
                    if (facts.getAcceptedAt() != null) {
                        long latency = ChronoUnit.MILLIS.between(facts.getAcceptedAt(), event.getOccurredAt());
                        facts.setAcceptToStartMs(Math.max(0L, latency));
                    }
                }
            }
            case JOB_COMPLETED -> {
                if (facts.getCompletedAt() == null) {
                    facts.setCompletedAt(event.getOccurredAt());
                }
                facts.setPaymentStatus(TransactionState.SUCCESS);
            }
            case BOOKING_CANCELLED -> {
                if (facts.getCancelledAt() == null) {
                    facts.setCancelledAt(event.getOccurredAt());
                }
            }
            case PAYMENT_STATUS_CHANGED -> {
                facts.setPaymentStatus(event.getPaymentStatus());
            }
            default -> {}
        }

        facts.setUpdatedAt(Instant.now());
        factsRepository.save(facts);
    }

    private void updateDailyAggregates(BookingEventEnvelope event) {
        LocalDate day = event.getOccurredAt() != null
                ? event.getOccurredAt().atZone(ZoneOffset.UTC).toLocalDate()
                : LocalDate.now(ZoneOffset.UTC);

        String pincode = event.getServicePincode() != null ? event.getServicePincode().trim() : "";

        BookingAnalyticsDaily.Key key = new BookingAnalyticsDaily.Key(day, pincode);
        BookingAnalyticsDaily daily = dailyRepository.findById(key)
                .orElseGet(() -> BookingAnalyticsDaily.builder()
                        .day(day)
                        .servicePincode(pincode)
                        .createdCount(0L)
                        .acceptedCount(0L)
                        .startedCount(0L)
                        .completedCount(0L)
                        .cancelledCount(0L)
                        .paymentSuccessCount(0L)
                        .paymentFailedCount(0L)
                        .paymentRefundedCount(0L)
                        .createToAcceptMsSum(0L)
                        .createToAcceptN(0L)
                        .acceptToStartMsSum(0L)
                        .acceptToStartN(0L)
                        .build());

        switch (event.getEventType()) {
            case BOOKING_CREATED -> daily.setCreatedCount(daily.getCreatedCount() + 1);
            case PARTNER_ACCEPTED -> {
                daily.setAcceptedCount(daily.getAcceptedCount() + 1);
                factsRepository.findById(event.getBookingId()).ifPresent(facts -> {
                    if (facts.getCreateToAcceptMs() != null) {
                        daily.setCreateToAcceptMsSum(daily.getCreateToAcceptMsSum() + facts.getCreateToAcceptMs());
                        daily.setCreateToAcceptN(daily.getCreateToAcceptN() + 1);
                    }
                });
            }
            case JOB_STARTED -> {
                daily.setStartedCount(daily.getStartedCount() + 1);
                factsRepository.findById(event.getBookingId()).ifPresent(facts -> {
                    if (facts.getAcceptToStartMs() != null) {
                        daily.setAcceptToStartMsSum(daily.getAcceptToStartMsSum() + facts.getAcceptToStartMs());
                        daily.setAcceptToStartN(daily.getAcceptToStartN() + 1);
                    }
                });
            }
            case JOB_COMPLETED -> {
                daily.setCompletedCount(daily.getCompletedCount() + 1);
                daily.setPaymentSuccessCount(daily.getPaymentSuccessCount() + 1);
            }
            case BOOKING_CANCELLED -> daily.setCancelledCount(daily.getCancelledCount() + 1);
            case PAYMENT_STATUS_CHANGED -> {
                if (event.getPaymentStatus() == TransactionState.SUCCESS) {
                    daily.setPaymentSuccessCount(daily.getPaymentSuccessCount() + 1);
                } else if (event.getPaymentStatus() == TransactionState.FAILED) {
                    daily.setPaymentFailedCount(daily.getPaymentFailedCount() + 1);
                } else if (event.getPaymentStatus() == TransactionState.REFUNDED) {
                    daily.setPaymentRefundedCount(daily.getPaymentRefundedCount() + 1);
                }
            }
            default -> {}
        }

        dailyRepository.save(daily);
    }
}
