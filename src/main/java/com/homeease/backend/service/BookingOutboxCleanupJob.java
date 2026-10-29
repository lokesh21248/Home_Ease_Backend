package com.homeease.backend.service;

import com.homeease.backend.model.enums.OutboxStatus;
import com.homeease.backend.repository.BookingEventOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@ConditionalOnProperty(name = "homeease.kafka.outbox.cleanup-enabled", havingValue = "true", matchIfMissing = true)
public class BookingOutboxCleanupJob {

    private static final Logger logger = LoggerFactory.getLogger(BookingOutboxCleanupJob.class);

    private final BookingEventOutboxRepository outboxRepository;

    @Value("${homeease.kafka.outbox.retention-days:7}")
    private int retentionDays;

    public BookingOutboxCleanupJob(BookingEventOutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    /**
     * Purges successfully published outbox rows older than the retention period (default 7 days).
     * Runs daily at 03:00 UTC (or configurable cron).
     */
    @Scheduled(cron = "${homeease.kafka.outbox.cleanup-cron:0 0 3 * * ?}")
    @Transactional
    public void cleanupPublishedOutboxEvents() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        logger.info("Executing scheduled cleanup of PUBLISHED outbox events older than {}", cutoff);

        try {
            int deleted = outboxRepository.deleteByStatusAndPublishedAtBefore(OutboxStatus.PUBLISHED, cutoff);
            logger.info("Outbox cleanup job completed successfully: deleted {} rows", deleted);
        } catch (Exception e) {
            logger.error("Error during scheduled outbox cleanup: {}", e.getMessage(), e);
        }
    }
}
