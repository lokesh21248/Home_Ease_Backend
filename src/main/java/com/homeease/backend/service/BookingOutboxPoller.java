package com.homeease.backend.service;

import com.homeease.backend.model.entity.BookingEventOutbox;
import com.homeease.backend.model.enums.OutboxStatus;
import com.homeease.backend.repository.BookingEventOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnProperty(name = "homeease.kafka.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class BookingOutboxPoller {

    private static final Logger logger = LoggerFactory.getLogger(BookingOutboxPoller.class);

    private final BookingEventOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${homeease.kafka.topics.booking-events:firstdoor.booking.events.v1}")
    private String bookingEventsTopic;

    @Value("${homeease.kafka.outbox.batch-size:50}")
    private int batchSize;

    @Value("${homeease.kafka.outbox.max-attempts:5}")
    private int maxAttempts;

    @Value("${homeease.kafka.outbox.initial-backoff-seconds:2}")
    private long initialBackoffSeconds;

    @Value("${homeease.kafka.outbox.max-backoff-seconds:300}")
    private long maxBackoffSeconds;

    @Value("${homeease.kafka.outbox.publish-timeout-seconds:5}")
    private long publishTimeoutSeconds;

    public BookingOutboxPoller(BookingEventOutboxRepository outboxRepository,
                               KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Periodic poller query using SKIP LOCKED to safely consume pending events.
     */
    @Scheduled(fixedDelayString = "${homeease.kafka.outbox.poll-interval-ms:500}")
    public void pollAndPublishPendingEvents() {
        List<BookingEventOutbox> pendingEvents;
        try {
            pendingEvents = outboxRepository.findPendingEventsForProcessing(Instant.now(), batchSize);
        } catch (Exception e) {
            // Fallback if dialect or in-memory DB doesn't support native SKIP LOCKED
            logger.debug("Falling back to standard poll query: {}", e.getMessage());
            pendingEvents = outboxRepository.findTopPending(
                    OutboxStatus.PENDING,
                    Instant.now(),
                    org.springframework.data.domain.PageRequest.of(0, batchSize)
            );
        }

        if (pendingEvents == null || pendingEvents.isEmpty()) {
            return;
        }

        logger.debug("Discovered {} pending outbox events for publishing", pendingEvents.size());

        for (BookingEventOutbox outbox : pendingEvents) {
            publishSingleEvent(outbox);
        }
    }

    @Transactional
    public void publishSingleEvent(BookingEventOutbox outbox) {
        String key = outbox.getAggregateId().toString();
        String payload = outbox.getPayload();

        try {
            CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(bookingEventsTopic, key, payload);
            SendResult<String, String> result = future.get(publishTimeoutSeconds, TimeUnit.SECONDS);

            outbox.setStatus(OutboxStatus.PUBLISHED);
            outbox.setPublishedAt(Instant.now());
            outbox.setLastError(null);
            outboxRepository.save(outbox);

            logger.info("Successfully published outbox event {} (type={}) to partition {} at offset {}",
                    outbox.getEventId(),
                    outbox.getEventType(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());

        } catch (Exception e) {
            int newAttemptCount = outbox.getAttemptCount() + 1;
            outbox.setAttemptCount(newAttemptCount);

            String errorMsg = e.getMessage() != null
                    ? e.getMessage().substring(0, Math.min(e.getMessage().length(), 500))
                    : e.getClass().getSimpleName();
            outbox.setLastError(errorMsg);

            if (newAttemptCount >= maxAttempts) {
                outbox.setStatus(OutboxStatus.FAILED);
                logger.error("Outbox event {} (type={}, aggregateId={}) exceeded max attempts ({}/{}). Marked as FAILED to prevent head-of-line blocking. Error: {}",
                        outbox.getEventId(), outbox.getEventType(), outbox.getAggregateId(), newAttemptCount, maxAttempts, errorMsg);
            } else {
                long delaySeconds = Math.min(maxBackoffSeconds, (long) (initialBackoffSeconds * Math.pow(2, newAttemptCount - 1)));
                outbox.setNextAttemptAt(Instant.now().plusSeconds(delaySeconds));
                logger.warn("Transient failure publishing outbox event {} (attempt {}/{}). Retrying in {}s. Error: {}",
                        outbox.getEventId(), newAttemptCount, maxAttempts, delaySeconds, errorMsg);
            }

            outboxRepository.save(outbox);
        }
    }
}
