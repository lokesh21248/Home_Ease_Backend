package com.homeease.backend.service;

import com.homeease.backend.repository.BookingRepository;
import com.homeease.backend.repository.BookingServiceItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Safety net for the instant booking flow.
 *
 * <p>A booking is saved first and dispatched afterwards, in separate transactions
 * (see {@code InstantBookingService.createInstantBooking}). If the process stops between the two, or the dispatch
 * transaction fails, the booking is left {@code REQUESTED} with no offers at all and nobody is looking for a worker.
 * This job finds exactly those bookings and dispatches them again.
 *
 * <p>It is deliberately narrow and safe to run on every instance at the same time:
 * <ul>
 *   <li>only INSTANT bookings that are still {@code REQUESTED} and have no assignment row at all;</li>
 *   <li>older than {@code min-age-seconds} (a normal dispatch takes well under a second or two, so a live one is never
 *       raced) and younger than {@code max-age-minutes} (old stuck bookings are never resurrected);</li>
 *   <li>at most {@code batch-size} per run;</li>
 *   <li>the dispatch itself locks the booking and re-checks its status, so concurrent runs, the request thread, the
 *       Kafka consumer and the retry endpoint can never offer the job to the same worker twice.</li>
 * </ul>
 * Disable with {@code homeease.dispatch.recovery.enabled=false}.
 */
@Service
@ConditionalOnProperty(name = "homeease.dispatch.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class InstantDispatchRecoveryJob {

    private static final Logger logger = LoggerFactory.getLogger(InstantDispatchRecoveryJob.class);

    private final BookingRepository bookingRepository;
    private final BookingServiceItemRepository bookingServiceItemRepository;
    private final BookingAssignmentService assignmentService;

    @Value("${homeease.dispatch.recovery.min-age-seconds:45}")
    private long minAgeSeconds = 45;

    @Value("${homeease.dispatch.recovery.max-age-minutes:15}")
    private long maxAgeMinutes = 15;

    @Value("${homeease.dispatch.recovery.batch-size:20}")
    private int batchSize = 20;

    public InstantDispatchRecoveryJob(BookingRepository bookingRepository,
                                      BookingServiceItemRepository bookingServiceItemRepository,
                                      BookingAssignmentService assignmentService) {
        this.bookingRepository = bookingRepository;
        this.bookingServiceItemRepository = bookingServiceItemRepository;
        this.assignmentService = assignmentService;
    }

    /** @return how many bookings were re-dispatched in this run. */
    @Scheduled(fixedDelayString = "${homeease.dispatch.recovery.interval-ms:30000}",
            initialDelayString = "${homeease.dispatch.recovery.initial-delay-ms:60000}")
    public int recoverUndispatchedInstantBookings() {
        Instant now = Instant.now();
        List<UUID> stuck = bookingRepository.findUndispatchedInstantBookingIds(
                now.minusSeconds(minAgeSeconds),
                now.minus(maxAgeMinutes, ChronoUnit.MINUTES),
                PageRequest.of(0, batchSize));

        int recovered = 0;
        for (UUID bookingId : stuck) {
            try {
                List<UUID> subServiceIds = bookingServiceItemRepository.findSubServiceIdsByBookingId(bookingId);
                assignmentService.dispatchInstantBookingById(bookingId, subServiceIds.isEmpty() ? null : subServiceIds.get(0));
                recovered++;
            } catch (Exception e) {
                // One failing booking must not stop the others; it is simply tried again on the next run.
                logger.error("[DISPATCH_RECOVERY_FAILED] bookingId={}: {}", bookingId, e.getMessage(), e);
            }
        }
        if (recovered > 0) {
            logger.warn("[DISPATCH_RECOVERED] re-dispatched {} instant booking(s) that were saved but never offered to a worker", recovered);
        }
        return recovered;
    }
}
