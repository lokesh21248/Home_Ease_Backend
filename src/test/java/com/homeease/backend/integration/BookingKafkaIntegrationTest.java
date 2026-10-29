package com.homeease.backend.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.dto.BookingEventDto;
import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.*;
import com.homeease.backend.repository.*;
import com.homeease.backend.service.BookingDomainEventService;
import com.homeease.backend.service.BookingHistoryProjectionConsumer;
import com.homeease.backend.service.BookingOutboxPoller;
import com.homeease.backend.service.BookingStateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DirtiesContext
@EmbeddedKafka(
        partitions = 3,
        topics = {"firstdoor.booking.events.v1", "firstdoor.booking.events.DLT"}
)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "homeease.kafka.topics.booking-events=firstdoor.booking.events.v1",
        "homeease.kafka.topics.booking-events-dlt=firstdoor.booking.events.DLT",
        "homeease.kafka.outbox.enabled=true",
        "homeease.kafka.outbox.poll-interval-ms=200",
        "homeease.kafka.outbox.publish-timeout-seconds=5"
})
class BookingKafkaIntegrationTest {

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private BookingEventHistoryRepository historyRepository;

    @Autowired
    private BookingEventOutboxRepository outboxRepository;

    @Autowired
    private BookingAnalyticsInboxRepository inboxRepository;

    @Autowired
    private BookingAnalyticsFactsRepository factsRepository;

    @Autowired
    private BookingAnalyticsDailyRepository dailyRepository;

    @Autowired
    private BookingDomainEventService domainEventService;

    @Autowired
    private BookingOutboxPoller outboxPoller;

    @Autowired
    private BookingHistoryProjectionConsumer historyProjectionConsumer;

    @Autowired
    private BookingStateMachine bookingStateMachine;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private User testUser;
    private ServiceEntity testService;

    @BeforeEach
    void setUp() {
        String randomSuffix = UUID.randomUUID().toString().substring(0, 8);
        testUser = userRepository.save(User.builder()
                .fullName("Kafka Test User " + randomSuffix)
                .phoneNumber("+91" + (1000000000L + (long) (Math.random() * 8999999999L)))
                .email("kafka." + randomSuffix + "@firstdoor.com")
                .role(UserRole.CUSTOMER)
                .build());

        testService = serviceRepository.save(ServiceEntity.builder()
                .name("Kafka Verification Vertical " + randomSuffix)
                .imageUrl("https://firstdoor.com/images/cleaning.png")
                .isActive(true)
                .build());
    }

    @Test
    @DisplayName("End-to-End: Outbox -> Kafka -> Independent Consumers -> Analytics Projection")
    void testEndToEndBookingKafkaPipeline() {
        // 1. Authoritative DB transaction: creates booking, records history, and creates outbox row
        UUID bookingId = transactionTemplate.execute(status -> {
            Booking booking = bookingRepository.save(Booking.builder()
                    .user(testUser)
                    .service(testService)
                    .status(BookingStage.SEARCHING)
                    .pinCode("1234")
                    .scheduledAt(Instant.now().plusSeconds(3600))
                    .userLat(17.3850)
                    .userLng(78.4867)
                    .baseAmount(new BigDecimal("499.00"))
                    .totalAmount(new BigDecimal("499.00"))
                    .paymentStatus(TransactionState.PENDING)
                    .paymentMethod(PaymentMode.UPI)
                    .servicePincode("500072")
                    .build());

            domainEventService.recordEvent(
                    booking,
                    BookingEventType.BOOKING_CREATED,
                    null,
                    BookingStage.SEARCHING,
                    BookingActorType.CUSTOMER,
                    testUser.getUserId()
            );

            return booking.getBookingId();
        });

        assertNotNull(bookingId);

        // 2. Verify authoritative PostgreSQL state immediately after transaction commits
        var historyRecords = historyRepository.findByBookingIdOrderByOccurredAtAsc(bookingId);
        assertEquals(1, historyRecords.size(), "Authoritative history must be recorded in DB transaction");
        UUID eventId = historyRecords.get(0).getEventId();

        var outboxRecords = outboxRepository.findAll().stream()
                .filter(o -> o.getAggregateId().equals(bookingId))
                .toList();
        assertEquals(1, outboxRecords.size(), "Outbox record must be created in DB transaction");
        assertEquals(OutboxStatus.PENDING, outboxRecords.get(0).getStatus());

        // 3. Trigger Outbox Poller
        outboxPoller.pollAndPublishPendingEvents();

        // Verify outbox transitioned to PUBLISHED
        var publishedOutbox = outboxRepository.findById(eventId).orElseThrow();
        assertEquals(OutboxStatus.PUBLISHED, publishedOutbox.getStatus());
        assertNotNull(publishedOutbox.getPublishedAt());

        // 4. Await asynchronous Kafka delivery to multiple independent consumer groups
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            // Group 1: Booking Analytics Consumer
            assertTrue(inboxRepository.existsById(eventId),
                    "Analytics consumer must process event and record in inbox");

            var facts = factsRepository.findById(bookingId);
            assertTrue(facts.isPresent(), "Booking analytics facts must be populated");
            assertEquals(BookingStage.SEARCHING, facts.get().getCurrentStatus());
            assertEquals("500072", facts.get().getServicePincode());

            // Group 2: Independent History Projection Consumer
            assertTrue(historyProjectionConsumer.getReceivedCount() > 0,
                    "History projection consumer must independently receive the Kafka stream");
        });

        // 5. Verify Authenticated Paginated History Query API
        BookingEventDto.BookingHistoryPage historyPage = bookingStateMachine.getBookingHistory(
                bookingId, testUser.getUserId(), false, 0, 10
        );
        assertEquals(1, historyPage.getTotalElements());
        assertEquals(BookingEventType.BOOKING_CREATED, historyPage.getContent().get(0).getEventType());
        assertEquals(BookingStage.SEARCHING, historyPage.getContent().get(0).getNewStatus());
    }
}
