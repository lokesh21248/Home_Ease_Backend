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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Supplier;

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
    private final UserAddressService userAddressService;
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
                                 BookingDomainEventService domainEventService,
                                 UserAddressService userAddressService) {
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
        this.userAddressService = userAddressService;
    }

    /** Used to open the booking transaction explicitly. Optional so unit tests that build the service by hand still work. */
    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;

    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    /** What the booking transaction produced. */
    private record CreatedBooking(BookingResponse response, UUID firstSubServiceId, boolean idempotentReplay) { }

    /**
     * Creates an instant booking and dispatches it to nearby workers, in three separate steps.
     *
     * <ol>
     *   <li><b>Booking transaction</b>: the booking, its line items and the domain event / outbox row are written and
     *       COMMITTED. Nothing else happens inside this transaction.</li>
     *   <li><b>Dispatch</b>: only after that commit, worker matching and the {@code booking_assignment} /
     *       audit rows are written in their own transaction, against the committed booking
     *       ({@link BookingAssignmentService#dispatchInstantBookingById}). It used to run inside the booking
     *       transaction via {@code REQUIRES_NEW}: the new transaction could not see the uncommitted booking, every
     *       insert referencing it violated a foreign key, the error was swallowed, and the booking was left
     *       {@code SEARCHING} with no assignments. A dispatch failure can no longer affect the booking: it stays
     *       {@code REQUESTED} and the recovery job dispatches it again.</li>
     *   <li><b>Response</b>: built from the committed state, so it reports what really happened
     *       ({@code SEARCHING}, or {@code WORKER_NOT_FOUND}), exactly as the API has always documented.</li>
     * </ol>
     */
    public BookingResponse createInstantBooking(UUID userId, InstantBookingRequest request, String idempotencyKey) {
        CreatedBooking created = inTransaction(false, () -> persistInstantBooking(userId, request, idempotencyKey));
        if (created.idempotentReplay()) {
            return created.response();
        }

        UUID bookingId = created.response().getBookingId();
        if (AfterCommit.inTransaction()) {
            // The caller itself runs inside a transaction that has not committed yet: dispatching now would not see
            // the booking. Dispatch as soon as that outer transaction commits instead.
            AfterCommit.run("instant booking dispatch " + bookingId,
                    () -> dispatch(bookingId, created.firstSubServiceId()));
            return created.response();
        }

        dispatch(bookingId, created.firstSubServiceId());
        return currentState(bookingId, created.response());
    }

    private void dispatch(UUID bookingId, UUID firstSubServiceId) {
        try {
            assignmentService.dispatchInstantBookingById(bookingId, firstSubServiceId);
        } catch (Exception dispatchEx) {
            logger.error("Instant booking {} was saved but dispatching it failed; it stays REQUESTED and will be "
                            + "dispatched again by the recovery job: {}",
                    bookingId, dispatchEx.getMessage(), dispatchEx);
        }
    }

    /** Re-reads the committed booking so the response reflects the dispatch outcome; falls back to the creation snapshot. */
    private BookingResponse currentState(UUID bookingId, BookingResponse fallback) {
        try {
            BookingResponse fresh = inTransaction(true,
                    () -> bookingRepository.findById(bookingId).map(this::mapToResponse).orElse(null));
            return fresh != null ? fresh : fallback;
        } catch (Exception e) {
            logger.warn("Could not re-read booking {} after dispatch, returning the creation snapshot: {}", bookingId, e.getMessage());
            return fallback;
        }
    }

    private <T> T inTransaction(boolean readOnly, Supplier<T> work) {
        if (transactionManager == null) {
            return work.get();
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(readOnly);
        return template.execute(status -> work.get());
    }

    /** Phase 1: runs inside the booking transaction. Must not dispatch or notify anyone. */
    private CreatedBooking persistInstantBooking(UUID userId, InstantBookingRequest request, String idempotencyKey) {
        logger.info("Creating Instant Booking for customer {} at ({}, {}) in pincode {}",
                userId, request.getUserLat(), request.getUserLng(), request.getPincode());

        // 1. Idempotency Guard
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = bookingRepository.findByIdempotencyKey(idempotencyKey.trim());
            if (existing.isPresent()) {
                return new CreatedBooking(mapToResponse(existing.get()), null, true);
            }
        }

        // 2. Resolve Address & Authoritative Pincode
        String resolvedPincode = request.getPincode();
        Double resolvedLat = request.getUserLat();
        Double resolvedLng = request.getUserLng();
        String resolvedAddress = request.getRecipientAddress();

        if (request.getAddressId() != null && !request.getAddressId().isBlank()) {
            var addrOpt = userAddressService.findUserAddress(userId, request.getAddressId().trim());
            if (addrOpt.isEmpty()) {
                throw new IllegalArgumentException("Invalid addressId: Selected address not found or unauthorized for customer " + userId);
            }
            var addr = addrOpt.get();
            if (addr.get("pincode") != null && !addr.get("pincode").toString().isBlank()) {
                resolvedPincode = addr.get("pincode").toString().replaceAll("\\D", "");
            }
            if (addr.get("lat") != null) {
                try { resolvedLat = Double.valueOf(addr.get("lat").toString()); } catch (Exception ignored) {}
            }
            if (addr.get("lng") != null) {
                try { resolvedLng = Double.valueOf(addr.get("lng").toString()); } catch (Exception ignored) {}
            }
            if (addr.get("addressLine") != null) {
                resolvedAddress = addr.get("addressLine").toString();
                if (addr.get("city") != null) {
                    resolvedAddress += ", " + addr.get("city").toString();
                }
            }
        }

        // Clean resolved pincode
        if (resolvedPincode != null) {
            resolvedPincode = resolvedPincode.replaceAll("\\D", "");
        }

        // Sanity check: If 500010 (Alwal/Bolarum) has stale KPHB coordinates (17.4947), correct to Alwal centroid
        if ("500010".equals(resolvedPincode) && (resolvedLat == null || Math.abs(resolvedLat - 17.4947) < 0.005)) {
            resolvedLat = 17.5028;
            resolvedLng = 78.5078;
        }

        if (resolvedPincode == null || !resolvedPincode.matches("^[0-9]{6}$")) {
            throw new IllegalArgumentException("A valid 6-digit Indian pincode is required for booking");
        }

        // 3. Validate Serviceability
        serviceAreaService.assertServiceable(resolvedPincode);

        // 4. Validate User
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + userId));

        // 5. Validate Service Vertical
        ServiceEntity service = serviceRepository.findById(request.getServiceId())
                .orElseThrow(() -> new ResourceNotFoundException("Service vertical not found: " + request.getServiceId()));

        Point userLocation = geometryFactory.createPoint(new Coordinate(resolvedLng, resolvedLat));

        // 6. Calculate Price and Estimated Service Duration
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
                .userLat(resolvedLat)
                .userLng(resolvedLng)
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
                .recipientAddress(resolvedAddress)
                .servicePincode(resolvedPincode)
                .build();

        booking = bookingRepository.save(booking);

        logger.info("[BOOKING_CREATED] bookingId={}, customerId={}, addressId={}", booking.getBookingId(), userId, request.getAddressId());
        logger.info("[BOOKING_LOCATION] bookingId={}, lat={}, lng={}, address={}", booking.getBookingId(), resolvedLat, resolvedLng, resolvedAddress);
        logger.info("[BOOKING_PINCODE] bookingId={}, pincode={}", booking.getBookingId(), resolvedPincode);
        logger.info("SERVICEABILITY_CHECK: bookingId={}, pincode={}, serviceId={}, result=SERVICEABLE",
                booking.getBookingId(), resolvedPincode, request.getServiceId());

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

        // Worker matching is deliberately NOT done here: it runs after this transaction commits (see createInstantBooking).
        return new CreatedBooking(mapToResponse(booking), firstSubServiceId, false);
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
                .pincode(b.getServicePincode())
                .customerAddress(b.getRecipientAddress())
                .build();
    }
}
