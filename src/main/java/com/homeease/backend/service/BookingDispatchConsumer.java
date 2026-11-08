package com.homeease.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.model.entity.Booking;
import com.homeease.backend.model.entity.BookingEvent;
import com.homeease.backend.model.entity.BookingServiceItem;
import com.homeease.backend.model.enums.BookingEventType;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.EventDeliveryStatus;
import com.homeease.backend.repository.BookingEventRepository;
import com.homeease.backend.repository.BookingRepository;
import com.homeease.backend.repository.BookingServiceItemRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class BookingDispatchConsumer {

    private static final Logger logger = LoggerFactory.getLogger(BookingDispatchConsumer.class);

    private final BookingRepository bookingRepository;
    private final BookingServiceItemRepository bookingServiceItemRepository;
    private final BookingAssignmentService assignmentService;
    private final BookingEventRepository bookingEventRepository;
    private final ObjectMapper objectMapper;

    public BookingDispatchConsumer(BookingRepository bookingRepository,
                                  BookingServiceItemRepository bookingServiceItemRepository,
                                  BookingAssignmentService assignmentService,
                                  BookingEventRepository bookingEventRepository,
                                  ObjectMapper objectMapper) {
        this.bookingRepository = bookingRepository;
        this.bookingServiceItemRepository = bookingServiceItemRepository;
        this.assignmentService = assignmentService;
        this.bookingEventRepository = bookingEventRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${homeease.kafka.topics.booking-events:firstdoor.booking.events.v1}",
            groupId = "homeease-dispatch-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void consumeBookingEvent(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            String payloadJson = record.value();
            JsonNode root = objectMapper.readTree(payloadJson);

            String eventTypeStr = root.has("eventType") ? root.get("eventType").asText() : "";
            String bookingIdStr = root.has("bookingId") ? root.get("bookingId").asText() : record.key();

            if (bookingIdStr == null || bookingIdStr.isBlank()) {
                if (ack != null) ack.acknowledge();
                return;
            }

            UUID bookingId = UUID.fromString(bookingIdStr);

            // Record event consumption audit log
            try {
                recordDisseminationLog(bookingId, eventTypeStr, payloadJson);
            } catch (Exception e) {
                logger.debug("Dissemination log write skipped: {}", e.getMessage());
            }

            // Only trigger spatial dispatch on BOOKING_CREATED
            if ("BOOKING_CREATED".equalsIgnoreCase(eventTypeStr)) {
                String servicePincode = root.has("servicePincode") && !root.get("servicePincode").isNull() ? root.get("servicePincode").asText() : null;
                Booking booking = bookingRepository.findById(bookingId).orElse(null);
                if (booking != null && (servicePincode == null || servicePincode.isBlank())) {
                    servicePincode = booking.getServicePincode();
                }
                logger.info("DISPATCH_RECEIVED: bookingId={}, pincode={}", bookingId, servicePincode);
                logger.info("Consumed BOOKING_CREATED Kafka event for booking {}", bookingId);

                if (booking != null && (booking.getStatus() == BookingStage.REQUESTED || booking.getStatus() == BookingStage.PENDING)) {
                    // Find first sub-service ID if present
                    UUID targetSubServiceId = null;
                    List<BookingServiceItem> items = bookingServiceItemRepository.findByBooking_BookingId(bookingId);
                    if (!items.isEmpty() && items.get(0).getSubService() != null) {
                        targetSubServiceId = items.get(0).getSubService().getSubServiceId();
                    }

                    assignmentService.dispatchInstantBooking(booking, targetSubServiceId);
                }
            }

            if (ack != null) {
                ack.acknowledge();
            }
        } catch (Exception e) {
            logger.error("Error processing booking dispatch Kafka event at offset {}: {}", record.offset(), e.getMessage());
            if (ack != null) {
                ack.acknowledge(); // Acknowledge to prevent infinite poison pill loop
            }
        }
    }

    private void recordDisseminationLog(UUID bookingId, String eventTypeStr, String payloadJson) {
        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null) return;

        BookingEventType type;
        try {
            type = BookingEventType.valueOf(eventTypeStr);
        } catch (Exception e) {
            type = BookingEventType.BOOKING_CREATED;
        }

        BookingEvent event = BookingEvent.builder()
                .booking(booking)
                .eventType(type)
                .payload(payloadJson)
                .publishedAt(Instant.now())
                .consumedBy("BookingDispatchConsumer")
                .deliveryStatus(EventDeliveryStatus.SENT)
                .attemptNo(1)
                .build();

        bookingEventRepository.save(event);
    }
}

