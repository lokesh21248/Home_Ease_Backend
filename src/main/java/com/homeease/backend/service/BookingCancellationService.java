package com.homeease.backend.service;

import com.homeease.backend.dto.BookingDto.BookingResponse;
import com.homeease.backend.exception.ResourceNotFoundException;
import com.homeease.backend.model.entity.Booking;
import com.homeease.backend.model.entity.BookingSlot;
import com.homeease.backend.model.entity.User;
import com.homeease.backend.model.enums.BookingActorType;
import com.homeease.backend.model.enums.BookingEventType;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.UserRole;
import com.homeease.backend.repository.BookingRepository;
import com.homeease.backend.repository.BookingSlotRepository;
import com.homeease.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class BookingCancellationService {

    private static final Logger logger = LoggerFactory.getLogger(BookingCancellationService.class);

    private final BookingRepository bookingRepository;
    private final BookingSlotRepository slotRepository;
    private final UserRepository userRepository;
    private final BookingNotificationService notificationService;
    private final BookingDomainEventService domainEventService;
    private final RedisTemplate<String, String> redisTemplate;

    public BookingCancellationService(BookingRepository bookingRepository,
                                      BookingSlotRepository slotRepository,
                                      UserRepository userRepository,
                                      BookingNotificationService notificationService,
                                      BookingDomainEventService domainEventService,
                                      RedisTemplate<String, String> redisTemplate) {
        this.bookingRepository = bookingRepository;
        this.slotRepository = slotRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.domainEventService = domainEventService;
        this.redisTemplate = redisTemplate;
    }

    @Transactional
    public BookingResponse cancelBooking(UUID bookingId, UUID callerUserId, String reason) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));

        if (booking.getStatus() == BookingStage.COMPLETED) {
            throw new IllegalStateException("Completed bookings cannot be cancelled.");
        }
        if (booking.getStatus() == BookingStage.IN_PROGRESS) {
            throw new IllegalStateException("Service is already in progress and cannot be cancelled directly. Please contact support.");
        }
        if (booking.getStatus() == BookingStage.CANCELLED) {
            return mapToResponse(booking); // Idempotent
        }

        User caller = userRepository.findById(callerUserId).orElse(null);
        boolean isCustomer = booking.getUser() != null && booking.getUser().getUserId().equals(callerUserId);
        boolean isWorker = booking.getWorker() != null && booking.getWorker().getUser() != null &&
                booking.getWorker().getUser().getUserId().equals(callerUserId);
        boolean isAdmin = caller != null && caller.getRole() == UserRole.ADMIN;

        if (!isCustomer && !isWorker && !isAdmin) {
            throw new SecurityException("User " + callerUserId + " is not authorized to cancel booking " + bookingId);
        }

        String roleLabel = isCustomer ? "Customer" : (isWorker ? "Partner" : "Support Admin");
        BookingActorType actorType = isCustomer ? BookingActorType.CUSTOMER : (isWorker ? BookingActorType.WORKER : BookingActorType.ADMIN);

        BookingStage previousStage = booking.getStatus();
        booking.setStatus(BookingStage.CANCELLED);
        booking.setCancellationReason(reason != null && !reason.isBlank() ? reason.trim() : "Cancelled by " + roleLabel);
        booking.setCancelledBy(caller);
        booking.setCancelledAt(Instant.now());
        booking = bookingRepository.save(booking);

        // Release slot if booking was scheduled
        if (booking.getSlotId() != null) {
            slotRepository.findById(booking.getSlotId()).ifPresent(slot -> {
                slot.setStatus("CANCELLED");
                slotRepository.save(slot);
            });
        }

        // Clean tracking caches in Redis
        try {
            redisTemplate.delete("tracking:assignment:" + bookingId);
            redisTemplate.delete("tracking:booking:" + bookingId);
        } catch (Exception ignored) {}

        // Record domain event
        domainEventService.recordEvent(
                booking,
                BookingEventType.BOOKING_CANCELLED,
                previousStage,
                BookingStage.CANCELLED,
                actorType,
                callerUserId
        );

        // Push notifications
        notificationService.notifyBookingCancelled(booking, booking.getCancellationReason(), roleLabel);

        return mapToResponse(booking);
    }

    public BookingResponse mapToResponse(Booking b) {
        return BookingResponse.builder()
                .bookingId(b.getBookingId())
                .userId(b.getUser() != null ? b.getUser().getUserId() : null)
                .userName(b.getUser() != null ? b.getUser().getFullName() : null)
                .userPhone(b.getUser() != null ? b.getUser().getPhoneNumber() : null)
                .workerId(b.getWorker() != null ? b.getWorker().getWorkerId() : null)
                .workerName(b.getWorker() != null && b.getWorker().getUser() != null ? b.getWorker().getUser().getFullName() : null)
                .workerPhone(b.getWorker() != null && b.getWorker().getUser() != null ? b.getWorker().getUser().getPhoneNumber() : null)
                .workerAvatarUrl(b.getWorker() != null ? b.getWorker().getAvatarUrl() : null)
                .serviceId(b.getService() != null ? b.getService().getServiceId() : null)
                .serviceName(b.getService() != null ? b.getService().getName() : null)
                .bookingType(b.getBookingType())
                .status(b.getStatus())
                .pinCode(b.getPinCode())
                .scheduledAt(b.getScheduledAt())
                .scheduledEndAt(b.getScheduledEndAt())
                .startedAt(b.getStartedAt())
                .completedAt(b.getCompletedAt())
                .workerAssignedAt(b.getWorkerAssignedAt())
                .estimatedArrivalMinutes(b.getEstimatedArrivalMinutes())
                .cancellationReason(b.getCancellationReason())
                .cancelledAt(b.getCancelledAt())
                .userLat(b.getUserLat())
                .userLng(b.getUserLng())
                .baseAmount(b.getBaseAmount())
                .extraAmount(b.getExtraAmount())
                .discountAmount(b.getDiscountAmount())
                .totalAmount(b.getTotalAmount())
                .paymentStatus(b.getPaymentStatus())
                .paymentMethod(b.getPaymentMethod())
                .createdAt(b.getCreatedAt())
                .isForFriend(b.getIsForFriend())
                .recipientName(b.getRecipientName())
                .recipientPhone(b.getRecipientPhone())
                .recipientAddress(b.getRecipientAddress())
                .servicePincode(b.getServicePincode())
                .build();
    }
}

