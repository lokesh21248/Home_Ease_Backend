package com.homeease.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.config.KafkaConfig;
import com.homeease.backend.model.entity.Booking;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.BookingType;
import com.homeease.backend.repository.BookingEventRepository;
import com.homeease.backend.repository.BookingRepository;
import com.homeease.backend.repository.BookingServiceItemRepository;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * The booking-event consumer against a REAL Kafka broker (the in-process broker of spring-kafka-test: real Kafka
 * server code, a throw-away temp directory and random local ports; nothing external is touched). It uses the project's
 * own {@link KafkaConfig} (listener container factory, error handler, dead-letter recoverer) and the real
 * {@link BookingDispatchConsumer}; only the database-facing collaborators are mocks.
 *
 * <p>What this protects: a failed dispatch event used to be swallowed and acknowledged inside the consumer, so it was
 * lost silently and neither the retries nor the dead-letter topic could ever run. It also covers the dead-letter
 * topic having fewer partitions than the source topic, which made the dead-letter publish itself fail.
 *
 * <p>This is NOT a production broker: it proves the consumer, retry and DLT wiring on a real Kafka protocol, not the
 * provisioned cluster, its security settings, topic configuration or throughput.
 */
@SpringJUnitConfig(classes = BookingDispatchConsumerKafkaTest.Config.class)
@EmbeddedKafka(partitions = 3, topics = {BookingDispatchConsumerKafkaTest.TOPIC},
        brokerProperties = {
                "offsets.topic.num.partitions=1",
                "offsets.topic.replication.factor=1",
                "transaction.state.log.replication.factor=1",
                "transaction.state.log.min.isr=1",
                "group.initial.rebalance.delay.ms=0"
        })
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.listener.concurrency=1",
        "homeease.kafka.topics.booking-events=" + BookingDispatchConsumerKafkaTest.TOPIC,
        "homeease.kafka.topics.booking-events-dlt=" + BookingDispatchConsumerKafkaTest.DLT
})
@DirtiesContext
class BookingDispatchConsumerKafkaTest {

    static final String TOPIC = "it.booking.events";
    static final String DLT = "it.booking.events.DLT";
    static final String GROUP = "homeease-dispatch-group";

    @Configuration
    @Import(KafkaConfig.class)
    static class Config {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean BookingRepository bookingRepository() { return mock(BookingRepository.class); }
        @Bean BookingServiceItemRepository bookingServiceItemRepository() { return mock(BookingServiceItemRepository.class); }
        @Bean BookingAssignmentService assignmentService() { return mock(BookingAssignmentService.class); }
        @Bean BookingEventRepository bookingEventRepository() { return mock(BookingEventRepository.class); }

        @Bean
        BookingDispatchConsumer bookingDispatchConsumer(BookingRepository bookings, BookingServiceItemRepository items,
                                                        BookingAssignmentService assignment, BookingEventRepository events,
                                                        ObjectMapper mapper) {
            return new BookingDispatchConsumer(bookings, items, assignment, events, mapper);
        }
    }

    @Autowired EmbeddedKafkaBroker broker;
    @Autowired BookingRepository bookingRepository;
    @Autowired BookingServiceItemRepository itemRepository;
    @Autowired BookingAssignmentService assignmentService;

    private Producer<String, String> producer;
    private Consumer<String, String> dltConsumer;
    private UUID bookingId;

    @BeforeEach
    void setUp() {
        reset(bookingRepository, itemRepository, assignmentService);
        bookingId = UUID.randomUUID();
        Booking waiting = Booking.builder().bookingId(bookingId).bookingType(BookingType.INSTANT)
                .status(BookingStage.REQUESTED).servicePincode("500081").build();
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(waiting));
        when(itemRepository.findByBooking_BookingId(bookingId)).thenReturn(Collections.emptyList());

        // the dead-letter topic has ONE partition while the source topic has THREE (the realistic way it gets provisioned)
        try {
            broker.addTopics(new NewTopic(DLT, 1, (short) 1));
        } catch (IllegalArgumentException alreadyCreatedByAnEarlierTest) {
            // the broker is shared by the tests of this class; the topic only has to exist once
        }

        producer = new DefaultKafkaProducerFactory<>(KafkaTestUtils.producerProps(broker),
                new StringSerializer(), new StringSerializer()).createProducer();
        Map<String, Object> props = KafkaTestUtils.consumerProps("dlt-verifier-" + UUID.randomUUID(), "true", broker);
        dltConsumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer()).createConsumer();
        // start reading the dead-letter topic from its END, so a record left there by an earlier test is never seen
        dltConsumer.subscribe(java.util.List.of(DLT));
        long assignDeadline = System.currentTimeMillis() + 15_000;
        while (dltConsumer.assignment().isEmpty() && System.currentTimeMillis() < assignDeadline) {
            dltConsumer.poll(Duration.ofMillis(200));
        }
        dltConsumer.seekToEnd(dltConsumer.assignment());
        dltConsumer.assignment().forEach(dltConsumer::position);

        committedBefore = committedOffsetOrZero();
    }

    private long committedBefore;

    private long committedOffsetOrZero() {
        try {
            return Math.max(0, committedOffset());
        } catch (Exception e) {
            return 0;
        }
    }

    @AfterEach
    void tearDown() {
        producer.close();
        dltConsumer.close();
    }

    private String bookingCreatedEvent() {
        return "{\"eventType\":\"BOOKING_CREATED\",\"bookingId\":\"" + bookingId + "\",\"servicePincode\":\"500081\"}";
    }

    /** Published to partition 2: with a one-partition dead-letter topic, "same partition number" cannot work. */
    private void publish(String payload) throws Exception {
        producer.send(new ProducerRecord<>(TOPIC, 2, bookingId.toString(), payload)).get();
        producer.flush();
    }

    private long committedOffset() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.<String, Object>of("bootstrap.servers", broker.getBrokersAsString()))) {
            Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(GROUP)
                    .partitionsToOffsetAndMetadata().get();
            OffsetAndMetadata committed = offsets.get(new TopicPartition(TOPIC, 2));
            return committed == null ? -1 : committed.offset();
        }
    }

    private void awaitCommittedOffset(long expected) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        long last = -1;
        while (System.currentTimeMillis() < deadline) {
            last = committedOffset();
            if (last >= expected) {
                return;
            }
            Thread.sleep(250);
        }
        assertThat(last).as("committed offset of the consumer group").isGreaterThanOrEqualTo(expected);
    }

    @Test
    @DisplayName("a dispatch that succeeds is dispatched once, acknowledged, and nothing reaches the dead-letter topic")
    void successfulDispatch_isAcknowledged_andNotDeadLettered() throws Exception {
        publish(bookingCreatedEvent());

        verify(assignmentService, timeout(30_000).times(1)).dispatchInstantBookingById(eq(bookingId), any());
        awaitCommittedOffset(committedBefore + 1);

        ConsumerRecords<String, String> dead = KafkaTestUtils.getRecords(dltConsumer, Duration.ofSeconds(3));
        assertThat(dead.count()).as("nothing failed, so nothing is dead-lettered").isZero();
        verify(assignmentService, times(1)).dispatchInstantBookingById(eq(bookingId), any());
    }

    @Test
    @DisplayName("a dispatch that fails is retried, then parked on the dead-letter topic (and the consumer moves on), never silently dropped")
    void failedDispatch_isRetried_thenDeadLettered_notSilentlyAcknowledged() throws Exception {
        doThrow(new IllegalStateException("database unavailable"))
                .when(assignmentService).dispatchInstantBookingById(eq(bookingId), any());
        String payload = bookingCreatedEvent();

        publish(payload);

        // 1 delivery + 3 retries (DefaultErrorHandler, 1 s apart), then the record is recovered onto the DLT
        ConsumerRecord<String, String> dead = KafkaTestUtils.getSingleRecord(dltConsumer, DLT, Duration.ofSeconds(60));
        assertThat(dead.value()).isEqualTo(payload);
        assertThat(dead.key()).isEqualTo(bookingId.toString());
        assertThat(dead.headers().lastHeader("kafka_dlt-exception-message")).as("failure reason travels with the record").isNotNull();
        verify(assignmentService, times(4)).dispatchInstantBookingById(eq(bookingId), any());

        // once parked, the consumer is past it: it is not redelivered forever
        awaitCommittedOffset(committedBefore + 1);
        Thread.sleep(2_500);
        verify(assignmentService, times(4)).dispatchInstantBookingById(eq(bookingId), any());
    }

    @Test
    @DisplayName("an unreadable event is dead-lettered instead of being acknowledged and forgotten")
    void poisonMessage_isDeadLettered() throws Exception {
        publish("this is not json");

        ConsumerRecord<String, String> dead = KafkaTestUtils.getSingleRecord(dltConsumer, DLT, Duration.ofSeconds(60));
        assertThat(dead.value()).isEqualTo("this is not json");
        awaitCommittedOffset(committedBefore + 1);
    }
}
