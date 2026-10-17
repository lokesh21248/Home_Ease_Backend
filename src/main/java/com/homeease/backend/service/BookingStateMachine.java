package com.homeease.backend.service;

import com.homeease.backend.dto.BookingDto.*;
import com.homeease.backend.dto.CatalogDto;
import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.*;
import com.homeease.backend.repository.*;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import com.homeease.backend.exception.InvalidPinException;
import com.homeease.backend.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BookingStateMachine {

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final ServiceRepository serviceRepository;
    private final SubServiceRepository subServiceRepository;
    private final ServiceAddonRepository addonRepository;
    private final BookingServiceItemRepository bookingServiceItemRepository;
    private final BookingAddonItemRepository bookingAddonItemRepository;
    private final CouponRepository couponRepository;
    private final ReviewRepository reviewRepository;
    private final DispatchEngine dispatchEngine;
    private final BillingEngine billingEngine;
    private final CatalogService catalogService;
    private final FcmNotificationService fcmNotificationService;
    private final org.springframework.data.redis.core.RedisTemplate<String, String> redisTemplate;
    private final org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    public BookingStateMachine(BookingRepository bookingRepository,
                               UserRepository userRepository,
                               ServiceRepository serviceRepository,
                               SubServiceRepository subServiceRepository,
                               ServiceAddonRepository addonRepository,
                               BookingServiceItemRepository bookingServiceItemRepository,
                               BookingAddonItemRepository bookingAddonItemRepository,
                               CouponRepository couponRepository,
                               ReviewRepository reviewRepository,
                               DispatchEngine dispatchEngine,
                               BillingEngine billingEngine,
                               CatalogService catalogService,
                               FcmNotificationService fcmNotificationService,
                               org.springframework.data.redis.core.RedisTemplate<String, String> redisTemplate,
                               org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate,
                               com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.serviceRepository = serviceRepository;
        this.subServiceRepository = subServiceRepository;
        this.addonRepository = addonRepository;
        this.bookingServiceItemRepository = bookingServiceItemRepository;
        this.bookingAddonItemRepository = bookingAddonItemRepository;
        this.couponRepository = couponRepository;
        this.reviewRepository = reviewRepository;
        this.dispatchEngine = dispatchEngine;
        this.billingEngine = billingEngine;
        this.catalogService = catalogService;
        this.fcmNotificationService = fcmNotificationService;
        this.redisTemplate = redisTemplate;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public BookingResponse createBooking(UUID userId, CreateBookingRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        ServiceEntity service = serviceRepository.findById(request.getServiceId())
                .orElseThrow(() -> new RuntimeException("Service vertical not found"));

        Point userLocation = geometryFactory.createPoint(new Coordinate(request.getUserLng(), request.getUserLat()));

        BigDecimal baseAmount = BigDecimal.ZERO;
        UUID firstSubServiceId = null;

        for (SubServiceRequestItem item : request.getSubServices()) {
            SubService subService = subServiceRepository.findById(item.getSubServiceId())
                    .orElseThrow(() -> new RuntimeException("Sub-service not found: " + item.getSubServiceId()));
            if (firstSubServiceId == null) {
                firstSubServiceId = subService.getSubServiceId();
            }
            BigDecimal itemTotal = subService.getBasePrice().multiply(BigDecimal.valueOf(item.getQuantity()));
            baseAmount = baseAmount.add(itemTotal);
        }

        BigDecimal addonAmount = BigDecimal.ZERO;
        if (request.getAddons() != null) {
            for (AddonRequestItem addonReq : request.getAddons()) {
                ServiceAddon addon = addonRepository.findById(addonReq.getAddonId())
                        .orElseThrow(() -> new RuntimeException("Addon not found: " + addonReq.getAddonId()));
                BigDecimal addonTotal = addon.getPrice().multiply(BigDecimal.valueOf(addonReq.getQuantity()));
                addonAmount = addonAmount.add(addonTotal);
            }
        }

        baseAmount = baseAmount.add(addonAmount);
        BigDecimal discountAmount = BigDecimal.ZERO;

        Coupon coupon = null;
        if (request.getCouponCode() != null && !request.getCouponCode().isBlank()) {
            var couponValidation = catalogService.validateCoupon(
                    CatalogDto.CouponValidateRequest.builder()
                            .code(request.getCouponCode())
                            .orderValue(baseAmount)
                            .build()
            );
            if (Boolean.TRUE.equals(couponValidation.getIsValid())) {
                discountAmount = couponValidation.getCalculatedDiscount();
                coupon = couponRepository.findByCodeAndIsActiveTrue(request.getCouponCode()).orElse(null);
                if (coupon != null) {
                    coupon.setTimesUsed(coupon.getTimesUsed() + 1);
                    couponRepository.save(coupon);
                }
            }
        }

        BigDecimal totalAmount = baseAmount.subtract(discountAmount);
        if (totalAmount.compareTo(BigDecimal.ZERO) < 0) {
            totalAmount = BigDecimal.ZERO;
        }

        Booking booking = Booking.builder()
                .user(user)
                .service(service)
                .coupon(coupon)
                .status(BookingStage.SEARCHING)
                .pinCode("0000") // Will be updated on worker acceptance
                .scheduledAt(request.getScheduledAt())
                .userLat(request.getUserLat())
                .userLng(request.getUserLng())
                .userLocation(userLocation)
                .baseAmount(baseAmount)
                .extraAmount(BigDecimal.ZERO)
                .discountAmount(discountAmount)
                .totalAmount(totalAmount)
                .paymentStatus(TransactionState.PENDING)
                .paymentMethod(request.getPaymentMethod())
                .build();

        booking = bookingRepository.save(booking);

        // Save booking line items
        for (SubServiceRequestItem item : request.getSubServices()) {
            SubService subService = subServiceRepository.findById(item.getSubServiceId()).get();
            BookingServiceItem lineItem = BookingServiceItem.builder()
                    .booking(booking)
                    .subService(subService)
                    .quantity(item.getQuantity())
                    .unitPrice(subService.getBasePrice())
                    .isAdditional(false)
                    .build();
            bookingServiceItemRepository.save(lineItem);
        }

        if (request.getAddons() != null) {
            for (AddonRequestItem addonReq : request.getAddons()) {
                ServiceAddon addon = addonRepository.findById(addonReq.getAddonId()).get();
                BookingAddonItem addonLineItem = BookingAddonItem.builder()
                        .booking(booking)
                        .addon(addon)
                        .quantity(addonReq.getQuantity())
                        .unitPrice(addon.getPrice())
                        .build();
                bookingAddonItemRepository.save(addonLineItem);
            }
        }

        // Trigger spatial candidate dispatch engine
        dispatchEngine.triggerSpatialDispatch(booking, firstSubServiceId);

        return mapToBookingResponse(booking);
    }

    @Transactional
    public BookingResponse verifyPinAndStartJob(UUID bookingId, UUID workerUserId, String submittedPin) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found with ID: " + bookingId));

        if (booking.getPinCode() == null || !booking.getPinCode().trim().equals(submittedPin != null ? submittedPin.trim() : "")) {
            throw new InvalidPinException("Invalid 4-digit verification PIN entered by worker.");
        }

        booking.setStatus(BookingStage.IN_PROGRESS);
        booking.setStartedAt(Instant.now());
        bookingRepository.save(booking);

        fcmNotificationService.sendPushNotification(
                booking.getUser().getUserId(),
                "Service Started!",
                "Worker " + booking.getWorker().getUser().getFullName() + " verified the PIN and started your service."
        );

        // Update tracking assignment in Redis to IN_PROGRESS
        try {
            String assignmentKey = "tracking:assignment:" + bookingId;
            String existingJson = redisTemplate.opsForValue().get(assignmentKey);
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            if (existingJson != null && !existingJson.isBlank()) {
                map = objectMapper.readValue(existingJson, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
            }
            map.put("bookingId", bookingId.toString());
            map.put("status", "IN_PROGRESS");
            map.put("startedAt", Instant.now().toString());
            redisTemplate.opsForValue().set(assignmentKey, objectMapper.writeValueAsString(map), java.time.Duration.ofHours(24));

            java.util.Map<String, Object> event = new java.util.HashMap<>();
            event.put("bookingId", bookingId.toString());
            event.put("status", "IN_PROGRESS");
            event.put("trackingActive", true);
            event.put("message", "Worker verified PIN and started service.");
            messagingTemplate.convertAndSend("/topic/track/" + bookingId, event);
            messagingTemplate.convertAndSend("/topic/tracking/" + bookingId, event);
        } catch (Exception ignored) {}

        return mapToBookingResponse(booking);
    }

    @Transactional
    public BookingResponse addExtraSubService(UUID bookingId, UUID workerUserId, AddExtraSubServiceRequest request) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found"));

        if (booking.getStatus() != BookingStage.IN_PROGRESS) {
            throw new RuntimeException("Extra line items can only be added while job is IN_PROGRESS");
        }

        SubService subService = subServiceRepository.findById(request.getSubServiceId())
                .orElseThrow(() -> new RuntimeException("Sub-service not found"));

        BigDecimal itemCost = subService.getBasePrice().multiply(BigDecimal.valueOf(request.getQuantity()));

        BookingServiceItem extraItem = BookingServiceItem.builder()
                .booking(booking)
                .subService(subService)
                .quantity(request.getQuantity())
                .unitPrice(subService.getBasePrice())
                .isAdditional(true)
                .build();
        bookingServiceItemRepository.save(extraItem);

        booking.setExtraAmount(booking.getExtraAmount().add(itemCost));
        booking.setTotalAmount(booking.getBaseAmount().add(booking.getExtraAmount()).subtract(booking.getDiscountAmount()));
        bookingRepository.save(booking);

        return mapToBookingResponse(booking);
    }

    @Transactional
    public BookingResponse completeJob(UUID bookingId, UUID workerUserId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found"));

        booking.setStatus(BookingStage.COMPLETED);
        booking.setCompletedAt(Instant.now());
        booking.setPaymentStatus(TransactionState.SUCCESS);
        bookingRepository.save(booking);

        // Execute billing & platform 2% commission settlement
        billingEngine.calculateAndPersistSettlement(booking);

        fcmNotificationService.sendPushNotification(
                booking.getUser().getUserId(),
                "Job Completed!",
                "Your service is completed. Total Amount: ₹" + booking.getTotalAmount() + ". Please rate your experience!"
        );

        // Stop live tracking: evict coordinates from Redis and broadcast completion event
        try {
            redisTemplate.delete("tracking:booking:" + bookingId);

            String assignmentKey = "tracking:assignment:" + bookingId;
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            map.put("bookingId", bookingId.toString());
            map.put("status", "COMPLETED");
            map.put("trackingActive", false);
            map.put("completedAt", Instant.now().toString());
            redisTemplate.opsForValue().set(assignmentKey, objectMapper.writeValueAsString(map), java.time.Duration.ofHours(1));

            java.util.Map<String, Object> event = new java.util.HashMap<>();
            event.put("bookingId", bookingId.toString());
            event.put("status", "COMPLETED");
            event.put("trackingActive", false);
            event.put("message", "Job completed. Live tracking has ended.");
            messagingTemplate.convertAndSend("/topic/track/" + bookingId, event);
            messagingTemplate.convertAndSend("/topic/tracking/" + bookingId, event);
        } catch (Exception ignored) {}

        return mapToBookingResponse(booking);
    }

    @Transactional
    public BookingResponse cancelBooking(UUID bookingId, UUID userId, String reason) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found with ID: " + bookingId));

        if (booking.getStatus() == BookingStage.COMPLETED) {
            throw new RuntimeException("Completed booking cannot be cancelled");
        }

        booking.setStatus(BookingStage.CANCELLED);
        bookingRepository.save(booking);

        // Stop live tracking: evict coordinates from Redis and broadcast cancellation event
        try {
            redisTemplate.delete("tracking:booking:" + bookingId);

            String assignmentKey = "tracking:assignment:" + bookingId;
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            map.put("bookingId", bookingId.toString());
            map.put("status", "CANCELLED");
            map.put("trackingActive", false);
            map.put("reason", reason != null ? reason : "Cancelled by user");
            map.put("cancelledAt", Instant.now().toString());
            redisTemplate.opsForValue().set(assignmentKey, objectMapper.writeValueAsString(map), java.time.Duration.ofHours(1));

            java.util.Map<String, Object> event = new java.util.HashMap<>();
            event.put("bookingId", bookingId.toString());
            event.put("status", "CANCELLED");
            event.put("trackingActive", false);
            event.put("message", "Booking has been cancelled. Live tracking ended.");
            messagingTemplate.convertAndSend("/topic/track/" + bookingId, event);
            messagingTemplate.convertAndSend("/topic/tracking/" + bookingId, event);
        } catch (Exception ignored) {}

        return mapToBookingResponse(booking);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> getUserBookings(UUID userId) {
        return bookingRepository.findByUser_UserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::mapToBookingResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> getWorkerBookings(UUID workerUserId) {
        return bookingRepository.findByWorker_WorkerIdOrderByCreatedAtDesc(workerUserId).stream()
                .map(this::mapToBookingResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public BookingResponse getBookingById(UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found"));
        return mapToBookingResponse(booking);
    }

    public BookingResponse mapToBookingResponse(Booking booking) {
        return BookingResponse.builder()
                .bookingId(booking.getBookingId())
                .userId(booking.getUser().getUserId())
                .userName(booking.getUser().getFullName())
                .userPhone(booking.getUser().getPhoneNumber())
                .workerId(booking.getWorker() != null ? booking.getWorker().getWorkerId() : null)
                .workerName(booking.getWorker() != null ? booking.getWorker().getUser().getFullName() : null)
                .workerPhone(booking.getWorker() != null ? booking.getWorker().getUser().getPhoneNumber() : null)
                .serviceId(booking.getService().getServiceId())
                .serviceName(booking.getService().getName())
                .status(booking.getStatus())
                .pinCode(booking.getPinCode())
                .scheduledAt(booking.getScheduledAt())
                .startedAt(booking.getStartedAt())
                .completedAt(booking.getCompletedAt())
                .userLat(booking.getUserLat())
                .userLng(booking.getUserLng())
                .baseAmount(booking.getBaseAmount())
                .extraAmount(booking.getExtraAmount())
                .discountAmount(booking.getDiscountAmount())
                .totalAmount(booking.getTotalAmount())
                .paymentStatus(booking.getPaymentStatus())
                .paymentMethod(booking.getPaymentMethod())
                .createdAt(booking.getCreatedAt())
                .build();
    }
}
