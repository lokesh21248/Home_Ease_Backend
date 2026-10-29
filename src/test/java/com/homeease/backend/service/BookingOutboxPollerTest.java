package com.homeease.backend.service;

import com.homeease.backend.model.entity.BookingEventOutbox;
import com.homeease.backend.model.enums.BookingEventType;
import com.homeease.backend.model.enums.OutboxStatus;
import com.homeease.backend.repository.BookingEventOutboxRepository;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingOutboxPollerTest {

    @Mock
    private BookingEventOutboxRepository outboxRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @InjectMocks
    private BookingOutboxPoller outboxPoller;

    private UUID eventId;
    private UUID aggregateId;
    private BookingEventOutbox pendingOutbox;

    @BeforeEach
    void setUp() {
        eventId = UUID.randomUUID();
        aggregateId = UUID.randomUUID();

        pendingOutbox = BookingEventOutbox.builder()
                .eventId(eventId)
                .aggregateId(aggregateId)
                .eventType(BookingEventType.BOOKING_CREATED)
                .payload("{\"eventId\":\"" + eventId + "\"}")
                .status(OutboxStatus.PENDING)
                .attemptCount(0)
                .nextAttemptAt(Instant.now())
                .createdAt(Instant.now())
                .build();

        ReflectionTestUtils.setField(outboxPoller, "bookingEventsTopic", "firstdoor.booking.events.v1");
        ReflectionTestUtils.setField(outboxPoller, "batchSize", 50);
        ReflectionTestUtils.setField(outboxPoller, "maxAttempts", 5);
        ReflectionTestUtils.setField(outboxPoller, "initialBackoffSeconds", 2L);
        ReflectionTestUtils.setField(outboxPoller, "maxBackoffSeconds", 300L);
        ReflectionTestUtils.setField(outboxPoller, "publishTimeoutSeconds", 5L);
    }

    @Test
    @DisplayName("publishSingleEvent marks outbox row as PUBLISHED on successful Kafka ACK")
    void testPublishSingleEvent_Success() {
        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition("firstdoor.booking.events.v1", 0),
                0L, 0, System.currentTimeMillis(), 0, 0
        );
        SendResult<String, String> sendResult = new SendResult<>(null, metadata);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        outboxPoller.publishSingleEvent(pendingOutbox);

        assertEquals(OutboxStatus.PUBLISHED, pendingOutbox.getStatus());
        assertNotNull(pendingOutbox.getPublishedAt());
        assertNull(pendingOutbox.getLastError());
        verify(outboxRepository, times(1)).save(pendingOutbox);
    }

    @Test
    @DisplayName("publishSingleEvent schedules retry with backoff on Kafka failure")
    void testPublishSingleEvent_TransientFailure_Retries() {
        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka broker unreachable"));

        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(failedFuture);

        outboxPoller.publishSingleEvent(pendingOutbox);

        assertEquals(OutboxStatus.PENDING, pendingOutbox.getStatus());
        assertEquals(1, pendingOutbox.getAttemptCount());
        assertTrue(pendingOutbox.getNextAttemptAt().isAfter(Instant.now()));
        assertTrue(pendingOutbox.getLastError().contains("Kafka broker unreachable"));
        verify(outboxRepository, times(1)).save(pendingOutbox);
    }

    @Test
    @DisplayName("publishSingleEvent marks outbox row as FAILED when max attempts exceeded")
    void testPublishSingleEvent_ExceedsMaxAttempts_MarksFailed() {
        pendingOutbox.setAttemptCount(4); // 4 previous attempts, this 5th one will exceed maxAttempts=5

        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Fatal serialization error"));

        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(failedFuture);

        outboxPoller.publishSingleEvent(pendingOutbox);

        assertEquals(OutboxStatus.FAILED, pendingOutbox.getStatus());
        assertEquals(5, pendingOutbox.getAttemptCount());
        verify(outboxRepository, times(1)).save(pendingOutbox);
    }

    @Test
    @DisplayName("pollAndPublishPendingEvents does nothing when no pending events exist")
    void testPollAndPublish_Empty() {
        when(outboxRepository.findPendingEventsForProcessing(any(Instant.class), anyInt()))
                .thenReturn(Collections.emptyList());

        outboxPoller.pollAndPublishPendingEvents();

        verifyNoInteractions(kafkaTemplate);
    }
}
