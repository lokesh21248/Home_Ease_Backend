package com.homeease.backend.service;

import com.homeease.backend.dto.BookingDto.BookingResponse;
import com.homeease.backend.dto.CartDto.CheckoutFromCartRequest;
import com.homeease.backend.exception.BadRequestException;
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

import java.security.SecureRandom;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Transactional
public class CartCheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CartCheckoutService.class);
    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");
    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*(AM|PM)?", Pattern.CASE_INSENSITIVE);

    private final BookingCartRepository cartRepository;
    private final BookingCartItemRepository cartItemRepository;
    private final BookingRepository bookingRepository;
    private final BookingServiceItemRepository bookingServiceItemRepository;
    private final BookingPricingDetailRepository pricingDetailRepository;
    private final BookingStatusHistoryRepository statusHistoryRepository;
    private final UserRepository userRepository;
    private final SubServiceRepository subServiceRepository;
    private final CouponRepository couponRepository;
    private final ServiceAreaService serviceAreaService;
    private final BookingAssignmentService assignmentService;
    private final BookingDomainEventService domainEventService;
    private final PricingEngineService pricingEngineService;
    private final GeometryFactory geometryFactory = new GeometryFactory();

    public CartCheckoutService(
            BookingCartRepository cartRepository,
            BookingCartItemRepository cartItemRepository,
            BookingRepository bookingRepository,
            BookingServiceItemRepository bookingServiceItemRepository,
            BookingPricingDetailRepository pricingDetailRepository,
            BookingStatusHistoryRepository statusHistoryRepository,
            UserRepository userRepository,
            SubServiceRepository subServiceRepository,
            CouponRepository couponRepository,
            ServiceAreaService serviceAreaService,
            BookingAssignmentService assignmentService,
            BookingDomainEventService domainEventService,
            PricingEngineService pricingEngineService) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.bookingRepository = bookingRepository;
        this.bookingServiceItemRepository = bookingServiceItemRepository;
        this.pricingDetailRepository = pricingDetailRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.userRepository = userRepository;
        this.subServiceRepository = subServiceRepository;
        this.couponRepository = couponRepository;
        this.serviceAreaService = serviceAreaService;
        this.assignmentService = assignmentService;
        this.domainEventService = domainEventService;
        this.pricingEngineService = pricingEngineService;
    }

    /**
     * Checkout from Cart to create an atomic Booking
     */
    public BookingResponse checkout(UUID userId, CheckoutFromCartRequest req) {
        log.info("Processing checkout for user: {}, bookingType: {}", userId, req.getBookingType());

        // 1. Idempotency Guard
        if (req.getIdempotencyKey() != null && !req.getIdempotencyKey().isBlank()) {
            Optional<Booking> existing = bookingRepository.findByIdempotencyKey(req.getIdempotencyKey().trim());
            if (existing.isPresent()) {
                log.info("Returning existing booking for idempotencyKey: {}", req.getIdempotencyKey());
                return mapToBookingResponse(existing.get());
            }
        }

        // 2. Validate Serviceability
        serviceAreaService.assertServiceable(req.getPincode());

        // 3. User verification
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        // 4. Cart verification
        BookingCart cart = cartRepository.findByUser_UserId(userId)
                .orElseThrow(() -> new BadRequestException("Cart is empty. Please add services before checkout."));

        List<BookingCartItem> items = cartItemRepository.findByCart_CartId(cart.getCartId());
        if (items.isEmpty()) {
            throw new BadRequestException("Cart is empty. Please add services before checkout.");
        }

        // 5. Server-authoritative Pricing Calculation
        PricingEngineService.PriceBreakdown pricing = pricingEngineService.calculateCartPricing(items, req.getCouponCode());

        // 6. Primary Service Vertical & SubService
        ServiceEntity primaryService = items.get(0).getService();
        UUID firstSubServiceId = null;

        // 7. Calculate Duration & Scheduling
        int totalMinutes = 0;
        for (BookingCartItem item : items) {
            int qty = item.getQuantity() != null ? item.getQuantity() : 1;
            if (item.getDuration() != null && item.getDuration().getMinutes() != null) {
                totalMinutes += item.getDuration().getMinutes() * qty;
            } else if (item.getServicePackage() != null && item.getServicePackage().getDurationMins() != null) {
                totalMinutes += item.getServicePackage().getDurationMins() * qty;
            } else if (item.getSubService() != null && item.getSubService().getEstimatedMins() != null) {
                totalMinutes += item.getSubService().getEstimatedMins() * qty;
            } else {
                totalMinutes += 60 * qty;
            }
        }
        totalMinutes = Math.max(30, totalMinutes);

        BookingType bType;
        try {
            bType = BookingType.valueOf(req.getBookingType().toUpperCase());
        } catch (Exception e) {
            bType = BookingType.INSTANT;
        }

        Instant scheduledAt;
        Instant scheduledEndAt;

        if (bType == BookingType.INSTANT) {
            scheduledAt = Instant.now();
            scheduledEndAt = scheduledAt.plus(totalMinutes, ChronoUnit.MINUTES);
        } else {
            scheduledAt = parseScheduledTime(req.getScheduledDate(), req.getTimeSlot());
            scheduledEndAt = scheduledAt.plus(totalMinutes, ChronoUnit.MINUTES);
        }

        Point userLocation = geometryFactory.createPoint(new Coordinate(req.getUserLng(), req.getUserLat()));
        String pinCode = String.format("%04d", new SecureRandom().nextInt(10000));

        // 8. Build & Save Booking
        Booking booking = Booking.builder()
                .user(user)
                .service(primaryService)
                .coupon(pricing.getAppliedCoupon())
                .bookingType(bType)
                .status(BookingStage.REQUESTED)
                .pinCode(pinCode)
                .scheduledAt(scheduledAt)
                .scheduledEndAt(scheduledEndAt)
                .userLat(req.getUserLat())
                .userLng(req.getUserLng())
                .userLocation(userLocation)
                .baseAmount(pricing.getSubtotal())
                .extraAmount(pricing.getAdditionalCharges())
                .discountAmount(pricing.getDiscountAmount())
                .totalAmount(pricing.getFinalPayableAmount())
                .paymentStatus(TransactionState.PENDING)
                .paymentMethod(req.getPaymentMethod())
                .idempotencyKey(req.getIdempotencyKey())
                .isForFriend(Boolean.TRUE.equals(req.getIsForFriend()))
                .recipientName(req.getRecipientName())
                .recipientPhone(req.getRecipientPhone())
                .recipientAddress(req.getAddress())
                .servicePincode(req.getPincode())
                .build();

        booking = bookingRepository.save(booking);

        // 9. Save Line Items & Resolve SubServices
        for (BookingCartItem item : items) {
            SubService sub = item.getSubService();
            if (sub == null) {
                List<SubService> existingSubs = subServiceRepository.findByService_ServiceIdAndIsActiveTrue(item.getService().getServiceId());
                if (!existingSubs.isEmpty()) {
                    sub = existingSubs.get(0);
                } else {
                    // Fallback to primary subservice
                    List<SubService> allSubs = subServiceRepository.findAll();
                    if (!allSubs.isEmpty()) {
                        sub = allSubs.get(0);
                    }
                }
            }

            if (firstSubServiceId == null && sub != null) {
                firstSubServiceId = sub.getSubServiceId();
            }

            if (sub != null) {
                BookingServiceItem bsi = BookingServiceItem.builder()
                        .booking(booking)
                        .subService(sub)
                        .quantity(item.getQuantity())
                        .unitPrice(item.getUnitPrice())
                        .isAdditional(false)
                        .build();
                bookingServiceItemRepository.save(bsi);
            }
        }

        // 10. Save Booking Pricing Detail Snapshot
        BookingPricingDetail pricingDetail = BookingPricingDetail.builder()
                .booking(booking)
                .baseServicePrice(pricing.getSubtotal())
                .additionalCharges(pricing.getAdditionalCharges())
                .taxAmount(pricing.getTaxAmount())
                .discountAmount(pricing.getDiscountAmount())
                .couponCode(pricing.getCouponCode())
                .finalPayableAmount(pricing.getFinalPayableAmount())
                .priceBreakdownJson(pricing.getBreakdownJson())
                .build();
        pricingDetailRepository.save(pricingDetail);

        // 11. Save Initial Status History
        BookingStatusHistory history = BookingStatusHistory.builder()
                .booking(booking)
                .fromStatus(null)
                .toStatus(BookingStage.REQUESTED.name())
                .changedBy("CUSTOMER")
                .reason("Booking placed successfully from cart checkout.")
                .build();
        statusHistoryRepository.save(history);

        // 12. If coupon applied, increment usage
        if (pricing.getAppliedCoupon() != null) {
            Coupon c = pricing.getAppliedCoupon();
            c.setTimesUsed(c.getTimesUsed() != null ? c.getTimesUsed() + 1 : 1);
            couponRepository.save(c);
        }

        // 13. Record Domain Event
        domainEventService.recordEvent(
                booking,
                BookingEventType.BOOKING_CREATED,
                null,
                BookingStage.REQUESTED,
                BookingActorType.CUSTOMER,
                userId,
                req.getIdempotencyKey(),
                null,
                null
        );

        // 14. Dispatch matching workers
        if (bType == BookingType.INSTANT && firstSubServiceId != null) {
            try {
                assignmentService.dispatchInstantBooking(booking, firstSubServiceId);
            } catch (Exception e) {
                log.warn("Instant dispatch exception (non-fatal, continuing checkout): {}", e.getMessage());
            }
        }

        // 15. Clear the user's cart
        cartItemRepository.deleteByCart_CartId(cart.getCartId());

        return mapToBookingResponse(booking);
    }

    private Instant parseScheduledTime(LocalDate date, String slot) {
        LocalDate targetDate = (date != null) ? date : LocalDate.now(IST_ZONE).plusDays(1);
        LocalTime startTime = LocalTime.of(10, 0);

        if (slot != null && !slot.isBlank()) {
            Matcher m = TIME_PATTERN.matcher(slot);
            if (m.find()) {
                int hour = Integer.parseInt(m.group(1));
                int min = Integer.parseInt(m.group(2));
                String ampm = m.group(3);
                if (ampm != null) {
                    if ("PM".equalsIgnoreCase(ampm) && hour < 12) hour += 12;
                    if ("AM".equalsIgnoreCase(ampm) && hour == 12) hour = 0;
                }
                startTime = LocalTime.of(hour, min);
            }
        }

        ZonedDateTime zdt = ZonedDateTime.of(targetDate, startTime, IST_ZONE);
        return zdt.toInstant();
    }

    private BookingResponse mapToBookingResponse(Booking b) {
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
                .userLat(b.getUserLat())
                .userLng(b.getUserLng())
                .baseAmount(b.getBaseAmount())
                .extraAmount(b.getExtraAmount())
                .discountAmount(b.getDiscountAmount())
                .totalAmount(b.getTotalAmount())
                .paymentStatus(b.getPaymentStatus())
                .paymentMethod(b.getPaymentMethod())
                .isForFriend(b.getIsForFriend())
                .recipientName(b.getRecipientName())
                .recipientPhone(b.getRecipientPhone())
                .recipientAddress(b.getRecipientAddress())
                .servicePincode(b.getServicePincode())
                .createdAt(b.getCreatedAt())
                .build();
    }
}

