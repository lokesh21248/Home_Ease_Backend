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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ScheduledBookingService {

    private static final Logger logger = LoggerFactory.getLogger(ScheduledBookingService.class);
    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");
    private static final Duration LOCK_EXPIRATION = Duration.ofSeconds(30);

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final ServiceRepository serviceRepository;
    private final SubServiceRepository subServiceRepository;
    private final ServiceAddonRepository addonRepository;
    private final BookingServiceItemRepository bookingServiceItemRepository;
    private final BookingAddonItemRepository bookingAddonItemRepository;
    private final CouponRepository couponRepository;
    private final BookingSlotRepository slotRepository;
    private final WorkerRepository workerRepository;
    private final WorkerServiceLinkRepository workerServiceLinkRepository;
    private final ServiceAreaService serviceAreaService;
    private final WorkerAvailabilityService availabilityService;
    private final BookingNotificationService notificationService;
    private final BookingDomainEventService domainEventService;
    private final RedisTemplate<String, String> redisTemplate;
    private final GeometryFactory geometryFactory = new GeometryFactory();

    public ScheduledBookingService(BookingRepository bookingRepository,
                                  UserRepository userRepository,
                                  ServiceRepository serviceRepository,
                                  SubServiceRepository subServiceRepository,
                                  ServiceAddonRepository addonRepository,
                                  BookingServiceItemRepository bookingServiceItemRepository,
                                  BookingAddonItemRepository bookingAddonItemRepository,
                                  CouponRepository couponRepository,
                                  BookingSlotRepository slotRepository,
                                  WorkerRepository workerRepository,
                                  WorkerServiceLinkRepository workerServiceLinkRepository,
                                  ServiceAreaService serviceAreaService,
                                  WorkerAvailabilityService availabilityService,
                                  BookingNotificationService notificationService,
                                  BookingDomainEventService domainEventService,
                                  RedisTemplate<String, String> redisTemplate) {
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.serviceRepository = serviceRepository;
        this.subServiceRepository = subServiceRepository;
        this.addonRepository = addonRepository;
        this.bookingServiceItemRepository = bookingServiceItemRepository;
        this.bookingAddonItemRepository = bookingAddonItemRepository;
        this.couponRepository = couponRepository;
        this.slotRepository = slotRepository;
        this.workerRepository = workerRepository;
        this.workerServiceLinkRepository = workerServiceLinkRepository;
        this.serviceAreaService = serviceAreaService;
        this.availabilityService = availabilityService;
        this.notificationService = notificationService;
        this.domainEventService = domainEventService;
        this.redisTemplate = redisTemplate;
    }

    @Transactional
    public BookingResponse createScheduledBooking(UUID userId, ScheduledBookingRequest request, String idempotencyKey) {
        logger.info("Creating Scheduled Booking for user {} on date {} slot {}",
                userId, request.getScheduledDate(), request.getTimeSlot());

        // 1. Idempotency Check
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = bookingRepository.findByIdempotencyKey(idempotencyKey.trim());
            if (existing.isPresent()) {
                return mapToResponse(existing.get());
            }
        }

        // 2. Validate Serviceability
        serviceAreaService.assertServiceable(request.getPincode());

        // 3. Parse Time Slot
        LocalTime slotStartTime = parseSlotStartTime(request.getTimeSlot());
        LocalDate scheduledDate = request.getScheduledDate();

        // Ensure date/time is at least 30 minutes in future in Asia/Kolkata
        ZonedDateTime slotStartIst = scheduledDate.atTime(slotStartTime).atZone(IST_ZONE);
        ZonedDateTime nowIst = ZonedDateTime.now(IST_ZONE);
        if (slotStartIst.isBefore(nowIst.plusMinutes(29))) {
            throw new IllegalArgumentException("Scheduled time must be at least 30 minutes in the future.");
        }

        Instant scheduledStartInstant = slotStartIst.toInstant();

        // 4. Redis Distributed Lock to prevent simultaneous slot reservations
        String lockKey = "lock:slot:" + request.getServiceId() + ":" + scheduledDate + ":" + slotStartTime.getHour();
        String lockToken = UUID.randomUUID().toString();
        Boolean acquired = false;
        try {
            acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, lockToken, LOCK_EXPIRATION);
        } catch (Exception e) {
            logger.warn("Redis distributed lock check skipped: {}", e.getMessage());
            acquired = true;
        }

        if (Boolean.FALSE.equals(acquired)) {
            throw new IllegalStateException("This time slot is currently being reserved by another customer. Please select another slot or retry in a few seconds.");
        }

        try {
            // 5. Validate User and Service Vertical
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + userId));

            ServiceEntity service = serviceRepository.findById(request.getServiceId())
                    .orElseThrow(() -> new ResourceNotFoundException("Service vertical not found: " + request.getServiceId()));

            // 6. Calculate Price and Service Duration
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

            Instant scheduledEndInstant = scheduledStartInstant.plus(Math.max(60, totalEstimatedMins), ChronoUnit.MINUTES);
            LocalTime slotEndTime = scheduledEndInstant.atZone(IST_ZONE).toLocalTime();

            // 7. Find available worker for this slot
            Worker assignedWorker = findBestAvailableWorkerForSlot(
                    firstSubServiceId, scheduledDate, scheduledStartInstant, scheduledEndInstant);

            String pinCode = String.format("%04d", new SecureRandom().nextInt(10000));
            Point userLocation = geometryFactory.createPoint(new Coordinate(request.getUserLng(), request.getUserLat()));

            Booking booking = Booking.builder()
                    .user(user)
                    .worker(assignedWorker)
                    .service(service)
                    .coupon(coupon)
                    .bookingType(BookingType.SCHEDULED)
                    .status(assignedWorker != null ? BookingStage.CONFIRMED : BookingStage.REQUESTED)
                    .pinCode(pinCode)
                    .scheduledAt(scheduledStartInstant)
                    .scheduledEndAt(scheduledEndInstant)
                    .workerAssignedAt(assignedWorker != null ? Instant.now() : null)
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

            // 8. Create BookingSlot reservation in DB
            if (assignedWorker != null) {
                BookingSlot slot = BookingSlot.builder()
                        .worker(assignedWorker)
                        .booking(booking)
                        .slotDate(scheduledDate)
                        .startTime(slotStartTime)
                        .endTime(slotEndTime)
                        .startTimestamp(scheduledStartInstant)
                        .endTimestamp(scheduledEndInstant)
                        .status("CONFIRMED")
                        .lockToken(lockToken)
                        .build();
                slot = slotRepository.save(slot);
                booking.setSlotId(slot.getSlotId());
                bookingRepository.save(booking);
            }

            // 9. Save Line Items
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

            // 10. Record Domain Event & Send Notifications
            domainEventService.recordEvent(
                    booking,
                    BookingEventType.BOOKING_CREATED,
                    null,
                    booking.getStatus(),
                    BookingActorType.CUSTOMER,
                    userId,
                    idempotencyKey,
                    null,
                    null
            );

            notificationService.notifyBookingConfirmed(booking);

            return mapToResponse(booking);
        } finally {
            // Release Redis slot lock
            try {
                String current = redisTemplate.opsForValue().get(lockKey);
                if (lockToken.equals(current)) {
                    redisTemplate.delete(lockKey);
                }
            } catch (Exception ignored) {}
        }
    }

    private Worker findBestAvailableWorkerForSlot(UUID subServiceId, LocalDate date,
                                                  Instant start, Instant end) {
        List<Worker> eligibleWorkers = new ArrayList<>();
        if (subServiceId != null) {
            var links = workerServiceLinkRepository.findById_SubServiceId(subServiceId);
            for (var link : links) {
                Worker w = link.getWorker();
                if (Boolean.TRUE.equals(w.getIsVerified())) {
                    eligibleWorkers.add(w);
                }
            }
        } else {
            eligibleWorkers = workerRepository.findAll().stream()
                    .filter(w -> Boolean.TRUE.equals(w.getIsVerified()))
                    .toList();
        }

        for (Worker worker : eligibleWorkers) {
            if (availabilityService.isWorkerAvailableForWindow(worker.getWorkerId(), start, end)) {
                return worker; // Best available match
            }
        }
        return null; // Will await assignment
    }

    private LocalTime parseSlotStartTime(String timeSlot) {
        if (timeSlot == null || timeSlot.isBlank()) return LocalTime.of(9, 0);

        // Matches e.g. "09:00 AM" or "9:00 AM" or "09:00"
        String startPart = timeSlot.contains("-") ? timeSlot.split("-")[0].trim() : timeSlot.trim();
        Matcher matcher = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*(AM|PM)?", Pattern.CASE_INSENSITIVE).matcher(startPart);
        if (matcher.find()) {
            int hrs = Integer.parseInt(matcher.group(1));
            int mins = Integer.parseInt(matcher.group(2));
            String mod = matcher.group(3);
            if (mod != null) {
                if ("PM".equalsIgnoreCase(mod) && hrs < 12) hrs += 12;
                if ("AM".equalsIgnoreCase(mod) && hrs == 12) hrs = 0;
            }
            return LocalTime.of(hrs, mins);
        }
        return LocalTime.of(9, 0);
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
