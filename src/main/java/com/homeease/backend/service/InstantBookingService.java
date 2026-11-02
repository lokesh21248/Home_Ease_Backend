package com.homeease.backend.service;

import com.homeease.backend.dto.BookingDto.*;
import com.homeease.backend.exception.ResourceNotFoundException;
import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.*;
import com.homeease.backend.repository.*;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class InstantBookingService {

    private static final Logger logger = LoggerFactory.getLogger(InstantBookingService.class);

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final ServiceRepository serviceRepository;
    private final SubServiceRepository subServiceRepository;
    private final ServiceAddonRepository addonRepository;
    private final BookingServiceItemRepository bookingServiceItemRepository;
    private final BookingAddonItemRepository bookingAddonItemRepository;
    private final CouponRepository couponRepository;
    private final ServiceAreaService serviceAreaService;
    private final BookingAssignmentService assignmentService;
    private final BookingDomainEventService domainEventService;
    private final GeometryFactory geometryFactory = new GeometryFactory();

    public InstantBookingService(BookingRepository bookingRepository,
                                 UserRepository userRepository,
                                 ServiceRepository serviceRepository,
                                 SubServiceRepository subServiceRepository,
                                 ServiceAddonRepository addonRepository,
                                 BookingServiceItemRepository bookingServiceItemRepository,
                                 BookingAddonItemRepository bookingAddonItemRepository,
                                 CouponRepository couponRepository,
                                 ServiceAreaService serviceAreaService,
                                 BookingAssignmentService assignmentService,
                                 BookingDomainEventService domainEventService) {
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.serviceRepository = serviceRepository;
        this.subServiceRepository = subServiceRepository;
        this.addonRepository = addonRepository;
        this.bookingServiceItemRepository = bookingServiceItemRepository;
        this.bookingAddonItemRepository = bookingAddonItemRepository;
        this.couponRepository = couponRepository;
        this.serviceAreaService = serviceAreaService;
        this.assignmentService = assignmentService;
        this.domainEventService = domainEventService;
    }

    @Transactional
    public BookingResponse createInstantBooking(UUID userId, InstantBookingRequest request, String idempotencyKey) {
        logger.info("Creating Instant Booking for customer {} at ({}, {}) in pincode {}",
                userId, request.getUserLat(), request.getUserLng(), request.getPincode());

        // 1. Idempotency Guard
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = bookingRepository.findByIdempotencyKey(idempotencyKey.trim());
            if (existing.isPresent()) {
                return mapToResponse(existing.get());
            }
        }

        // 2. Validate Serviceability
        serviceAreaService.assertServiceable(request.getPincode());

        // 3. Validate User
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + userId));

        // 4. Validate Service Vertical
        ServiceEntity service = serviceRepository.findById(request.getServiceId())
                .orElseThrow(() -> new ResourceNotFoundException("Service vertical not found: " + request.getServiceId()));

        Point userLocation = geometryFactory.createPoint(new Coordinate(request.getUserLng(), request.getUserLat()));

        // 5. Calculate Price and Estimated Service Duration
        BigDecimal baseAmount = BigDecimal.ZERO;
        int totalEstimatedMins = 0;
        UUID firstSubServiceId = null;

        for (SubServiceRequestItem item : request.getSubServices()) {
            SubService sub = subServiceRepository.findById(item.getSubServiceId())
                    .orElseThrow(() -> new ResourceNotFoundException("Sub-service not found: " + item.getSubServiceId()));
            if (firstSubServiceId == null) {
                firstSubServiceId = sub.getSubServiceId();
            }
            baseAmount = baseAmount.add(sub.getBasePrice().multiply(BigDecimal.valueOf(item.getQuantity())));
            totalEstimatedMins += sub.getEstimatedMins() * item.getQuantity();
        }

        BigDecimal addonAmount = BigDecimal.ZERO;
        if (request.getAddons() != null) {
            for (AddonRequestItem add : request.getAddons()) {
                ServiceAddon addon = addonRepository.findById(add.getAddonId())
                        .orElseThrow(() -> new ResourceNotFoundException("Addon not found: " + add.getAddonId()));
                addonAmount = addonAmount.add(addon.getPrice().multiply(BigDecimal.valueOf(add.getQuantity())));
            }
        }

        baseAmount = baseAmount.add(addonAmount);
        BigDecimal discountAmount = BigDecimal.ZERO;
        Coupon coupon = null;

        if (request.getCouponCode() != null && !request.getCouponCode().isBlank()) {
            var couponOpt = couponRepository.findByCodeAndIsActiveTrue(request.getCouponCode().trim().toUpperCase());
            if (couponOpt.isPresent()) {
                coupon = couponOpt.get();
                if (coupon.getDiscountType() == DiscountModel.PERCENTAGE) {
                    discountAmount = baseAmount.multiply(coupon.getDiscountVal()).divide(BigDecimal.valueOf(100));
                    if (coupon.getMaxDiscountAmount() != null && discountAmount.compareTo(coupon.getMaxDiscountAmount()) > 0) {
                        discountAmount = coupon.getMaxDiscountAmount();
                    }
                } else {
                    discountAmount = coupon.getDiscountVal();
                }
            }
        }

        BigDecimal totalAmount = baseAmount.subtract(discountAmount);
        if (totalAmount.compareTo(BigDecimal.ZERO) < 0) {
            totalAmount = BigDecimal.ZERO;
        }

        // Instant booking schedules immediately
        Instant now = Instant.now();
        Instant scheduledEnd = now.plus(Math.max(60, totalEstimatedMins), ChronoUnit.MINUTES);
        String pinCode = String.format("%04d", new SecureRandom().nextInt(10000));

        Booking booking = Booking.builder()
                .user(user)
                .service(service)
                .coupon(coupon)
                .bookingType(BookingType.INSTANT)
                .status(BookingStage.REQUESTED)
                .pinCode(pinCode)
                .scheduledAt(now)
                .scheduledEndAt(scheduledEnd)
                .userLat(request.getUserLat())
                .userLng(request.getUserLng())
                .userLocation(userLocation)
                .baseAmount(baseAmount)
                .extraAmount(BigDecimal.ZERO)
                .discountAmount(discountAmount)
                .totalAmount(totalAmount)
                .paymentStatus(TransactionState.PENDING)
                .paymentMethod(request.getPaymentMethod())
                .idempotencyKey(idempotencyKey)
                .isForFriend(Boolean.TRUE.equals(request.getIsForFriend()))
                .recipientName(request.getRecipientName())
                .recipientPhone(request.getRecipientPhone())
                .recipientAddress(request.getRecipientAddress())
                .servicePincode(request.getPincode())
                .build();

        booking = bookingRepository.save(booking);

        // Save Line Items
        for (SubServiceRequestItem item : request.getSubServices()) {
            SubService sub = subServiceRepository.findById(item.getSubServiceId()).orElse(null);
            if (sub != null) {
                BookingServiceItem bsi = BookingServiceItem.builder()
                        .booking(booking)
                        .subService(sub)
                        .quantity(item.getQuantity())
                        .unitPrice(sub.getBasePrice())
                        .isAdditional(false)
                        .build();
                bookingServiceItemRepository.save(bsi);
            }
        }

        if (request.getAddons() != null) {
            for (AddonRequestItem add : request.getAddons()) {
                ServiceAddon addon = addonRepository.findById(add.getAddonId()).orElse(null);
                if (addon != null) {
                    BookingAddonItem bai = BookingAddonItem.builder()
                            .booking(booking)
                            .addon(addon)
                            .quantity(add.getQuantity())
                            .unitPrice(addon.getPrice())
                            .build();
                    bookingAddonItemRepository.save(bai);
                }
            }
        }

        // Record domain event
        domainEventService.recordEvent(
                booking,
                BookingEventType.BOOKING_CREATED,
                null,
                BookingStage.REQUESTED,
                BookingActorType.CUSTOMER,
                userId,
                idempotencyKey,
                null,
                null
        );

        // Dispatch candidate alerts to nearby workers
        assignmentService.dispatchInstantBooking(booking, firstSubServiceId);

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
