package com.homeease.backend.service;

import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.AssignmentStatus;
import com.homeease.backend.model.enums.BookingActorType;
import com.homeease.backend.model.enums.BookingEventType;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class ReassignmentService {

    private static final Logger logger = LoggerFactory.getLogger(ReassignmentService.class);

    private final BookingRepository bookingRepository;
    private final BookingAssignmentRepository assignmentRepository;
    private final ReassignmentTriggerLogRepository triggerLogRepository;
    private final BookingAssignmentService assignmentService;
    private final BookingDomainEventService domainEventService;

    public ReassignmentService(BookingRepository bookingRepository,
                               BookingAssignmentRepository assignmentRepository,
                               ReassignmentTriggerLogRepository triggerLogRepository,
                               BookingAssignmentService assignmentService,
                               BookingDomainEventService domainEventService) {
        this.bookingRepository = bookingRepository;
        this.assignmentRepository = assignmentRepository;
        this.triggerLogRepository = triggerLogRepository;
        this.assignmentService = assignmentService;
        this.domainEventService = domainEventService;
    }

    /**
     * Executes reassignment triggered by a specific condition (Section 7).
     * Bumps version, appends booking_event, drops previous assignment, and re-engages assignment engine.
     */
    @Transactional
    public void triggerReassignment(UUID bookingId, String triggerType, String reason, String triggeredBy) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));

        Worker previousWorker = booking.getWorker();
        UUID previousWorkerId = previousWorker != null ? previousWorker.getWorkerId() : null;

        logger.warn("Reassignment triggered for booking {}: type={}, reason={}, previousWorker={}",
                bookingId, triggerType, reason, previousWorkerId);

        // 1. Record in reassignment_trigger_log
        ReassignmentTriggerLog log = ReassignmentTriggerLog.builder()
                .booking(booking)
                .previousWorker(previousWorker)
                .triggerType(triggerType)
                .reason(reason)
                .triggeredBy(triggeredBy != null ? triggeredBy : "SYSTEM")
                .build();
        triggerLogRepository.save(log);

        // 2. Clear current worker assignment & drop pending/accepted assignment records
        if (previousWorkerId != null) {
            assignmentRepository.findByBooking_BookingIdAndWorker_WorkerId(bookingId, previousWorkerId)
                    .ifPresent(a -> {
                        a.setStatus(AssignmentStatus.DROPPED);
                        a.setRespondedAt(Instant.now());
                        assignmentRepository.save(a);
                    });
        }
        booking.setWorker(null);
        booking.setStatus(BookingStage.SEARCHING);
        booking = bookingRepository.save(booking);

        // 3. Append booking_event (Non-negotiable Rule #8)
        try {
            domainEventService.recordEvent(
                    booking,
                    BookingEventType.PARTNER_REASSIGNED,
                    BookingStage.CONFIRMED,
                    BookingStage.SEARCHING,
                    BookingActorType.SYSTEM,
                    null
            );
        } catch (Exception e) {
            logger.warn("Domain event record skipped: {}", e.getMessage());
        }

        // 4. Re-dispatch booking to next eligible candidate
        UUID subServiceId = null;
        assignmentService.dispatchInstantBooking(booking, subServiceId);
    }

    /**
     * Periodic automated detector for Section 7 triggers:
     * 1. NO_TRAVEL_AT_T_MINUS_30: Worker hasn't started travel by T-30 minutes for a scheduled booking.
     * 2. STALE_GPS_TELEMETRY: Worker is ON_THE_WAY but hasn't sent GPS in > 15 minutes.
     */
    @Scheduled(fixedDelay = 60000) // Check every 60 seconds
    @Transactional
    public void scanAndTriggerAutomatedReassignments() {
        Instant now = Instant.now();

        // Check 1: NO_TRAVEL_AT_T_MINUS_30
        Instant tMinus30Threshold = now.plus(30, ChronoUnit.MINUTES);
        List<Booking> upcomingBookings = bookingRepository.findByStatus(BookingStage.CONFIRMED);
        for (Booking b : upcomingBookings) {
            if (b.getScheduledAt() != null && b.getScheduledAt().isBefore(tMinus30Threshold) && b.getWorker() != null) {
                logger.warn("Booking {} reached T-30 without worker starting travel. Triggering reassignment.", b.getBookingId());
                triggerReassignment(
                        b.getBookingId(),
                        "NO_TRAVEL_AT_T_MINUS_30",
                        "Worker did not start travel 30 minutes before scheduled slot start.",
                        "SYSTEM_MONITOR"
                );
            }
        }

        // Check 2: STALE_GPS_TELEMETRY
        List<Booking> travellingBookings = bookingRepository.findByStatus(BookingStage.ON_THE_WAY);
        Instant staleGpsCutoff = now.minus(15, ChronoUnit.MINUTES);
        for (Booking b : travellingBookings) {
            if (b.getWorker() != null && b.getWorker().getLastLocationUpdatedAt() != null) {
                if (b.getWorker().getLastLocationUpdatedAt().isBefore(staleGpsCutoff)) {
                    logger.warn("Booking {} worker has stale GPS (>15m). Triggering reassignment.", b.getBookingId());
                    triggerReassignment(
                            b.getBookingId(),
                            "STALE_GPS_TELEMETRY",
                            "Worker GPS telemetry stale for more than 15 minutes during active travel.",
                            "TELEMETRY_WATCHDOG"
                    );
                }
            }
        }
    }
}

