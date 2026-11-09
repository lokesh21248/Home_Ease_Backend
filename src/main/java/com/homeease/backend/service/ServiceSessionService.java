package com.homeease.backend.service;

import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.*;
import com.homeease.backend.repository.BookingRepository;
import com.homeease.backend.repository.PaymentRepository;
import com.homeease.backend.repository.ServiceSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class ServiceSessionService {

    private static final Logger logger = LoggerFactory.getLogger(ServiceSessionService.class);

    private final ServiceSessionRepository sessionRepository;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final BookingDomainEventService domainEventService;
    private final FcmNotificationService fcmNotificationService;
    private final SimpMessagingTemplate messagingTemplate;

    public ServiceSessionService(ServiceSessionRepository sessionRepository,
                                 BookingRepository bookingRepository,
                                 PaymentRepository paymentRepository,
                                 BookingDomainEventService domainEventService,
                                 FcmNotificationService fcmNotificationService,
                                 SimpMessagingTemplate messagingTemplate) {
        this.sessionRepository = sessionRepository;
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
        this.domainEventService = domainEventService;
        this.fcmNotificationService = fcmNotificationService;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Atomically starts a service session after successful OTP verification.
     */
    @Transactional
    public ServiceSession startSession(Booking booking, Worker worker) {
        if (booking == null || worker == null) {
            throw new IllegalArgumentException("Booking and Worker must not be null");
        }

        Optional<ServiceSession> existing = sessionRepository.findActiveSessionForBooking(booking.getBookingId());
        if (existing.isPresent()) {
            return existing.get();
        }

        Instant startedAt = Instant.now();

        ServiceSession session = ServiceSession.builder()
                .booking(booking)
                .worker(worker)
                .customer(booking.getUser())
                .startedAt(startedAt)
                .status(ServiceSessionStatus.ACTIVE)
                .build();

        session = sessionRepository.save(session);

        booking.setStatus(BookingStage.IN_PROGRESS);
        booking.setStartedAt(startedAt);
        bookingRepository.save(booking);

        try {
            domainEventService.recordEvent(
                    booking,
                    BookingEventType.SERVICE_STARTED,
                    BookingStage.ARRIVED,
                    BookingStage.IN_PROGRESS,
                    BookingActorType.WORKER,
                    worker.getUser() != null ? worker.getUser().getUserId() : null
            );
        } catch (Exception e) {
            logger.warn("Domain event record skipped: {}", e.getMessage());
        }

        broadcastSessionEvent("SERVICE_STARTED", booking, session, startedAt);

        logger.info("Service session started for booking {} at {}", booking.getBookingId(), startedAt);
        return session;
    }

    /**
     * Get active session information and backend-calculated elapsed seconds.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getSessionInfo(UUID bookingId) {
        ServiceSession session = sessionRepository.findByBooking_BookingId(bookingId)
                .orElseThrow(() -> new NoSuchElementException("No service session found for booking " + bookingId));

        Instant now = Instant.now();
        Instant endInstant = session.getCompletedAt() != null ? session.getCompletedAt() : now;
        long elapsedSeconds = Math.max(0, Duration.between(session.getStartedAt(), endInstant).getSeconds());

        Map<String, Object> response = new HashMap<>();
        response.put("sessionId", session.getSessionId().toString());
        response.put("bookingId", bookingId.toString());
        response.put("workerId", session.getWorker().getWorkerId().toString());
        response.put("customerId", session.getCustomer().getUserId().toString());
        response.put("startedAt", session.getStartedAt().toString());
        if (session.getCompletedAt() != null) {
            response.put("completedAt", session.getCompletedAt().toString());
        }
        response.put("status", session.getStatus().name());
        response.put("elapsedSeconds", elapsedSeconds);
        response.put("durationMinutes", session.getDurationMinutes());
        response.put("finalPrice", session.getFinalPrice());
        return response;
    }

    /**
     * Completes an active service session, computes final price, and updates earnings.
     */
    @Transactional
    public ServiceSession completeSession(Booking booking, UUID workerUserId) {
        if (booking == null) {
            throw new IllegalArgumentException("Booking cannot be null");
        }

        if (booking.getWorker() == null || booking.getWorker().getUser() == null ||
                !booking.getWorker().getUser().getUserId().equals(workerUserId)) {
            throw new SecurityException("Unauthorized: Only the assigned worker may complete this service.");
        }

        ServiceSession session = sessionRepository.findActiveSessionForBooking(booking.getBookingId())
                .orElseThrow(() -> new IllegalStateException("No active service session for booking " + booking.getBookingId()));

        Instant completedAt = Instant.now();
        BigDecimal durationMinutes = BigDecimal.valueOf(
                Duration.between(session.getStartedAt(), completedAt).toSeconds() / 60.0
        ).setScale(2, RoundingMode.HALF_UP);

        BigDecimal finalPrice = calculateFinalPrice(booking);

        session.setCompletedAt(completedAt);
        session.setDurationMinutes(durationMinutes);
        session.setFinalPrice(finalPrice);
        session.setStatus(ServiceSessionStatus.COMPLETED);
        session = sessionRepository.save(session);

        booking.setStatus(BookingStage.COMPLETED);
        booking.setCompletedAt(completedAt);
        booking.setPaymentStatus(TransactionState.SUCCESS);
        booking.setTotalAmount(finalPrice);
        booking.setFinalPrice(finalPrice);
        bookingRepository.save(booking);

        recordWorkerEarnings(booking, finalPrice);

        try {
            domainEventService.recordEvent(
                    booking,
                    BookingEventType.SERVICE_COMPLETED,
                    BookingStage.IN_PROGRESS,
                    BookingStage.COMPLETED,
                    BookingActorType.WORKER,
                    workerUserId
            );
        } catch (Exception e) {
            logger.warn("Domain event record skipped: {}", e.getMessage());
        }

        broadcastSessionEvent("SERVICE_COMPLETED", booking, session, completedAt);

        if (booking.getUser() != null) {
            try {
                fcmNotificationService.sendPushNotification(
                        booking.getUser().getUserId(),
                        "Service Completed!",
                        "Your service for " + (booking.getService() != null ? booking.getService().getName() : "booking") +
                                " is completed. Total Amount: ₹" + finalPrice
                );
            } catch (Exception e) {
                logger.debug("FCM push failed for service completion: {}", e.getMessage());
            }
        }

        logger.info("Service session completed for booking {} with duration {} mins, finalPrice ₹{}",
                booking.getBookingId(), durationMinutes, finalPrice);
        return session;
    }

    public BigDecimal calculateFinalPrice(Booking booking) {
        if (booking == null) return BigDecimal.ZERO;
        BigDecimal base = booking.getBaseAmount() != null ? booking.getBaseAmount() : BigDecimal.ZERO;
        BigDecimal extra = booking.getExtraAmount() != null ? booking.getExtraAmount() : BigDecimal.ZERO;
        BigDecimal discount = booking.getDiscountAmount() != null ? booking.getDiscountAmount() : BigDecimal.ZERO;
        BigDecimal finalPrice = base.add(extra).subtract(discount);
        return finalPrice.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : finalPrice;
    }

    private void recordWorkerEarnings(Booking booking, BigDecimal totalAmount) {
        try {
            BigDecimal platformCommissionRate = new BigDecimal("0.15"); // 15%
            BigDecimal commission = totalAmount.multiply(platformCommissionRate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal netPayout = totalAmount.subtract(commission);

            Payment payment = Payment.builder()
                    .booking(booking)
                    .grossAmount(totalAmount)
                    .platformCommissionPercent(new BigDecimal("15.00"))
                    .platformCommissionAmount(commission)
                    .workerPayoutAmount(netPayout)
                    .status(TransactionState.SUCCESS)
                    .build();
            paymentRepository.save(payment);
        } catch (Exception e) {
            logger.warn("Worker payment record exception: {}", e.getMessage());
        }
    }

    private void broadcastSessionEvent(String eventType, Booking booking, ServiceSession session, Instant timestamp) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", eventType);
            payload.put("bookingId", booking.getBookingId().toString());
            payload.put("sessionId", session.getSessionId().toString());
            payload.put("status", booking.getStatus().name());
            payload.put("timestamp", timestamp.toString());
            payload.put("finalPrice", session.getFinalPrice());

            messagingTemplate.convertAndSend("/topic/booking/" + booking.getBookingId(), payload);
            messagingTemplate.convertAndSend("/topic/track/" + booking.getBookingId(), payload);
            messagingTemplate.convertAndSend("/topic/tracking/" + booking.getBookingId(), payload);
            if (booking.getUser() != null) {
                messagingTemplate.convertAndSend("/topic/customer/" + booking.getUser().getUserId() + "/booking", payload);
            }
            if (session.getWorker() != null && session.getWorker().getUser() != null) {
                messagingTemplate.convertAndSend("/topic/worker/" + session.getWorker().getUser().getUserId() + "/booking", payload);
            }
        } catch (Exception e) {
            logger.debug("WebSocket broadcast skipped in session service: {}", e.getMessage());
        }
    }
}
