package com.homeease.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.model.entity.Booking;
import com.homeease.backend.model.entity.BookingEvent;
import com.homeease.backend.model.entity.BookingServiceItem;
import com.homeease.backend.model.enums.BookingEventType;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.BookingType;
import com.homeease.backend.model.enums.EventDeliveryStatus;
import com.homeease.backend.repository.BookingEventRepository;
import com.homeease.backend.repository.BookingRepository;
import com.homeease.backend.repository.BookingServiceItemRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
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
    // No Acknowledgment parameter on purpose: the container uses AckMode.RECORD (KafkaConfig), which commits the offset
    // itself once this method returns normally and leaves it uncommitted (retry, then dead letter) when it throws.
    // An Acknowledgment can only be supplied in a MANUAL ack mode; declaring one made the container fail EVERY record
    // before this method body ran ("No Acknowledgment available as an argument"), so no event was ever dispatched
    // and every one ended up on the dead-letter topic.
    public void consumeBookingEvent(ConsumerRecord<String, String> record) {
        try {
            String payloadJson = record.value();
            JsonNode root = objectMapper.readTree(payloadJson);

            String eventTypeStr = root.has("eventType") ? root.get("eventType").asText() : "";
            String bookingIdStr = root.has("bookingId") ? root.get("bookingId").asText() : record.key();

            if (bookingIdStr == null || bookingIdStr.isBlank()) {
                return;
            }

            UUID bookingId = UUID.fromString(bookingIdStr);

            // Only trigger spatial dispatch on BOOKING_CREATED
            if ("BOOKING_CREATED".equalsIgnoreCase(eventTypeStr)) {
                String servicePincode = root.has("servicePincode") && !root.get("servicePincode").isNull() ? root.get("servicePincode").asText() : null;
                Booking booking = bookingRepository.findById(bookingId).orElse(null);
                if (booking != null && (servicePincode == null || servicePincode.isBlank())) {
                    servicePincode = booking.getServicePincode();
                }
                logger.info("DISPATCH_RECEIVED: bookingId={}, pincode={}", bookingId, servicePincode);
                logger.info("Consumed BOOKING_CREATED Kafka event for booking {}", bookingId);

                // Only INSTANT bookings are matched immediately. Unassigned SCHEDULED bookings are also saved as
                // REQUESTED and are offered later by the T-120 batch assigner; dispatching them here would alert
                // workers about a job that may be days away.
                if (booking != null && booking.getBookingType() == BookingType.INSTANT
                        && (booking.getStatus() == BookingStage.REQUESTED || booking.getStatus() == BookingStage.PENDING)) {
                    // Find first sub-service ID if present
                    UUID targetSubServiceId = null;
                    List<BookingServiceItem> items = bookingServiceItemRepository.findByBooking_BookingId(bookingId);
                    if (!items.isEmpty() && items.get(0).getSubService() != null) {
                        targetSubServiceId = items.get(0).getSubService().getSubServiceId();
                    }

                    // By ID: dispatch loads and locks the booking in its own transaction, re-checks its status under
                    // that lock (a redelivered event, the request thread and the recovery job may all get here for
                    // the same booking) and never mutates the entity this consumer transaction holds.
                    assignmentService.dispatchInstantBookingById(bookingId, targetSubServiceId);
                }
            }

            // Record event consumption audit log. Written AFTER the dispatch on purpose: this insert references the
            // booking row (foreign key) and so locks it for as long as this transaction is open, and the dispatch above
            // runs in its own transaction and needs that same row locked FOR UPDATE. Done first, the dispatch would
            // wait for this transaction while this transaction waits for the dispatch: a deadlock the database cannot
            // see.
            try {
                recordDisseminationLog(bookingId, eventTypeStr, payloadJson);
            } catch (Exception e) {
                logger.debug("Dissemination log write skipped: {}", e.getMessage());
            }

        } catch (Exception e) {
            // A failed dispatch must NOT be acknowledged. The exception goes back to the listener container, whose
            // DefaultErrorHandler (see KafkaConfig) retries the record and then parks it on the dead-letter topic. It
            // used to be swallowed here and acknowledged, so a failure was silently lost and neither the retries nor the
            // DLT could ever run. The booking itself is not lost either way: it stays REQUESTED and
            // InstantDispatchRecoveryJob dispatches it.
            logger.error("Error processing booking dispatch Kafka event at offset {}: {}", record.offset(), e.getMessage());
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Booking dispatch event failed at offset " + record.offset(), e);
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

