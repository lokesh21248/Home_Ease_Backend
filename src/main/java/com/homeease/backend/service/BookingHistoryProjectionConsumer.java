package com.homeease.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.dto.BookingEventDto.BookingEventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

@Service
public class BookingHistoryProjectionConsumer {

    private static final Logger logger = LoggerFactory.getLogger(BookingHistoryProjectionConsumer.class);

    private final ObjectMapper objectMapper;
    private final AtomicLong receivedCount = new AtomicLong(0);

    public BookingHistoryProjectionConsumer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Independent consumer group demonstration. Receives the identical stream of booking events
     * with completely independent offset tracking, ensuring zero interference with analytics.
     */
    @KafkaListener(
            topics = "${homeease.kafka.topics.booking-events:firstdoor.booking.events.v1}",
            groupId = "firstdoor-booking-history",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeHistoryStream(String messagePayload) {
        try {
            BookingEventEnvelope event = objectMapper.readValue(messagePayload, BookingEventEnvelope.class);
            long count = receivedCount.incrementAndGet();
            logger.info("Group [firstdoor-booking-history] received event #{}: id={}, type={}, bookingId={}",
                    count, event.getEventId(), event.getEventType(), event.getBookingId());
        } catch (Exception e) {
            logger.warn("Group [firstdoor-booking-history] failed to deserialize message: {}", e.getMessage());
        }
    }

    public long getReceivedCount() {
        return receivedCount.get();
    }
}
