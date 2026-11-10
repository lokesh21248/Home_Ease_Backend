package com.homeease.backend.controller;

import com.homeease.backend.dto.BookingDto.*;
import com.homeease.backend.dto.WorkerDto.WorkerBookingRequestResponse;
import com.homeease.backend.exception.ResourceNotFoundException;
import com.homeease.backend.model.entity.Booking;
import com.homeease.backend.model.entity.BookingEvent;
import com.homeease.backend.model.entity.Worker;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.BookingType;
import com.homeease.backend.repository.BookingEventRepository;
import com.homeease.backend.repository.BookingRepository;
import com.homeease.backend.repository.BookingServiceItemRepository;
import com.homeease.backend.repository.WorkerRepository;
import com.homeease.backend.security.JwtService;
import com.homeease.backend.service.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

@RestController
@RequestMapping({"/api/v1", "/api"})
@CrossOrigin(origins = "*")
public class BookingController {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(BookingController.class);

    private final BookingStateMachine bookingStateMachine;
    private final DispatchEngine dispatchEngine;
    private final InstantBookingService instantBookingService;
    private final ScheduledBookingService scheduledBookingService;
    private final WorkerAvailabilityService availabilityService;
    private final BookingAssignmentService assignmentService;
    private final BookingCancellationService cancellationService;
    private final BookingOtpService bookingOtpService;
    private final ServiceSessionService sessionService;
    private final BookingEventRepository bookingEventRepository;
    private final BookingRepository bookingRepository;
    private final BookingServiceItemRepository bookingServiceItemRepository;
    private final WorkerRepository workerRepository;
    private final com.homeease.backend.repository.ReviewRepository reviewRepository;
    private final JwtService jwtService;
    private final com.homeease.backend.service.LedgerService ledgerService;
    private final com.homeease.backend.service.FraudDetectionService fraudDetectionService;

    public BookingController(BookingStateMachine bookingStateMachine,
                             DispatchEngine dispatchEngine,
                             InstantBookingService instantBookingService,
                             ScheduledBookingService scheduledBookingService,
                             WorkerAvailabilityService availabilityService,
                             BookingAssignmentService assignmentService,
                             BookingCancellationService cancellationService,
                             BookingOtpService bookingOtpService,
                             ServiceSessionService sessionService,
                             BookingEventRepository bookingEventRepository,
                             BookingRepository bookingRepository,
                             BookingServiceItemRepository bookingServiceItemRepository,
                             WorkerRepository workerRepository,
                             com.homeease.backend.repository.ReviewRepository reviewRepository,
                             JwtService jwtService,
                             com.homeease.backend.service.LedgerService ledgerService,
                             com.homeease.backend.service.FraudDetectionService fraudDetectionService) {
        this.bookingStateMachine = bookingStateMachine;
        this.dispatchEngine = dispatchEngine;
        this.instantBookingService = instantBookingService;
        this.scheduledBookingService = scheduledBookingService;
        this.availabilityService = availabilityService;
        this.assignmentService = assignmentService;
        this.cancellationService = cancellationService;
        this.bookingOtpService = bookingOtpService;
        this.sessionService = sessionService;
        this.bookingEventRepository = bookingEventRepository;
        this.bookingRepository = bookingRepository;
        this.bookingServiceItemRepository = bookingServiceItemRepository;
        this.workerRepository = workerRepository;
        this.reviewRepository = reviewRepository;
        this.jwtService = jwtService;
        this.ledgerService = ledgerService;
        this.fraudDetectionService = fraudDetectionService;
    }

    private UUID resolveUserId(String authHeader, String headerUserIdStr, UUID queryUserId) {
        return com.homeease.backend.security.SecurityUtils.resolveAuthenticatedUserId(queryUserId, headerUserIdStr);
    }

    // ─────────────────────────────────────────────────────────────────
    // DUAL BOOKING SYSTEM ENDPOINTS
    // ─────────────────────────────────────────────────────────────────

    @PostMapping("/bookings/instant")
    public ResponseEntity<BookingResponse> createInstantBooking(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody InstantBookingRequest request) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);
        return ResponseEntity.ok(instantBookingService.createInstantBooking(userId, request, idempotencyKey));
    }

    @PostMapping("/bookings/scheduled")
    public ResponseEntity<BookingResponse> createScheduledBooking(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ScheduledBookingRequest request) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);
        return ResponseEntity.ok(scheduledBookingService.createScheduledBooking(userId, request, idempotencyKey));
    }

    @GetMapping("/bookings/available-dates")
    public ResponseEntity<List<AvailableDateDto>> getAvailableDates(
            @RequestParam(value = "serviceId", required = false) UUID serviceId,
            @RequestParam(value = "subServiceId", required = false) UUID subServiceId,
            @RequestParam(value = "days", defaultValue = "14") int days) {
        return ResponseEntity.ok(availabilityService.getAvailableDates(serviceId, subServiceId, days));
    }

    @GetMapping({"/bookings/available-slots", "/bookings/slots"})
    public ResponseEntity<List<AvailableSlotDto>> getDynamicAvailableSlots(
            @RequestParam(value = "date", required = false) String dateStr,
            @RequestParam(value = "serviceId", required = false) UUID serviceId,
            @RequestParam(value = "subServiceId", required = false) UUID subServiceId) {
        LocalDate date = (dateStr != null && !dateStr.isBlank())
                ? LocalDate.parse(dateStr.trim())
                : LocalDate.now(WorkerAvailabilityService.IST_ZONE);
        return ResponseEntity.ok(availabilityService.getAvailableTimeSlots(date, serviceId, subServiceId));
    }

    @GetMapping("/bookings/nearby-workers")
    public ResponseEntity<List<NearbyWorkerDto>> getNearbyWorkers(
            @RequestParam("lat") double lat,
            @RequestParam("lng") double lng,
            @RequestParam(value = "serviceId", required = false) UUID serviceId,
            @RequestParam(value = "subServiceId", required = false) UUID subServiceId,
            @RequestParam(value = "radius", required = false) Double radius) {
        return ResponseEntity.ok(assignmentService.findNearbyAvailableWorkers(serviceId, subServiceId, lat, lng, radius));
    }

    @GetMapping("/bookings/{id}/status")
    public ResponseEntity<Map<String, Object>> getBookingStatus(
            @PathVariable("id") UUID bookingId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "userId", required = false) UUID queryUserId) {
        BookingResponse resp = bookingStateMachine.getBookingById(bookingId);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("bookingId", resp.getBookingId());
        map.put("status", resp.getStatus());
        map.put("bookingType", resp.getBookingType());
        map.put("workerAssigned", resp.getWorkerId() != null);
        map.put("workerId", resp.getWorkerId());
        map.put("workerName", resp.getWorkerName());
        map.put("workerPhone", resp.getWorkerPhone());
        map.put("workerAvatarUrl", resp.getWorkerAvatarUrl());

        // PIN is strictly confidential: never exposed to worker or third-parties.
        // Only the authenticated customer receives it when status is ARRIVED.
        String pinForCustomer = null;
        try {
            UUID callerId = resolveUserId(authHeader, headerUserId, queryUserId);
            Booking booking = bookingRepository.findById(bookingId).orElse(null);
            if (booking != null && booking.getUser() != null &&
                    booking.getUser().getUserId().equals(callerId) &&
                    booking.getStatus() == BookingStage.ARRIVED) {
                pinForCustomer = bookingOtpService.getCustomerPin(booking);
            }
        } catch (Exception ignored) {
            // Unauthenticated or worker -> pin remains null
        }

        map.put("pinCode", pinForCustomer);
        map.put("scheduledAt", resp.getScheduledAt());
        map.put("estimatedArrivalMinutes", resp.getEstimatedArrivalMinutes());
        map.put("trackingActive", resp.getStatus() != null &&
                (resp.getStatus().name().equals("ACCEPTED") ||
                 resp.getStatus().name().equals("ON_THE_WAY") ||
                 resp.getStatus().name().equals("ARRIVED") ||
                 resp.getStatus().name().equals("IN_PROGRESS")));
        return ResponseEntity.ok(map);
    }

    /**
     * Dedicated customer-authorized endpoint to retrieve the 4-digit Service Start PIN.
     * Accessible ONLY by the authenticated customer who placed the booking.
     * Available ONLY after the service partner marks ARRIVED.
     */
    @GetMapping({"/bookings/{id}/service-pin", "/bookings/{id}/pin"})
    public ResponseEntity<Map<String, Object>> getServiceStartPin(
            @PathVariable("id") UUID bookingId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "userId", required = false) UUID queryUserId) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);

        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));

        // 1. Verify that the authenticated customer owns the booking
        if (booking.getUser() == null || !booking.getUser().getUserId().equals(userId)) {
            throw new SecurityException("Unauthorized: Only the customer who placed this booking can access the Service Start PIN.");
        }

        // 2. Before ARRIVED, do not reveal the PIN
        if (booking.getStatus() != BookingStage.ARRIVED) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("bookingId", bookingId.toString());
            resp.put("pinCode", null);
            resp.put("status", booking.getStatus().name());
            resp.put("message", "Service Start PIN is only revealed once your service partner marks ARRIVED at your location.");
            return ResponseEntity.status(400).body(resp);
        }

        String pin = bookingOtpService.getCustomerPin(booking);
        if (pin == null || pin.isBlank()) {
            bookingOtpService.issueOtp(booking);
            pin = bookingOtpService.getCustomerPin(booking);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("bookingId", bookingId.toString());
        response.put("pinCode", pin);
        response.put("status", booking.getStatus().name());
        response.put("message", "Share this PIN with your service partner to start the service.");
        return ResponseEntity.ok(response);
    }

    // ─────────────────────────────────────────────────────────────────
    // CUSTOMER UNIFIED ENDPOINTS
    // ─────────────────────────────────────────────────────────────────

    @PostMapping("/bookings")
    public ResponseEntity<BookingResponse> createBooking(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateBookingRequest request) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);

        if (request.getBookingType() == BookingType.SCHEDULED ||
            (request.getScheduledAt() != null && request.getScheduledAt().isAfter(java.time.Instant.now().plus(45, java.time.temporal.ChronoUnit.MINUTES)))) {
            LocalDate date = request.getScheduledDate() != null
                    ? request.getScheduledDate()
                    : (request.getScheduledAt() != null
                            ? request.getScheduledAt().atZone(WorkerAvailabilityService.IST_ZONE).toLocalDate()
                            : LocalDate.now(WorkerAvailabilityService.IST_ZONE));
            String timeSlot = (request.getTimeSlot() != null && !request.getTimeSlot().isBlank())
                    ? request.getTimeSlot()
                    : (request.getScheduledAt() != null
                            ? request.getScheduledAt().atZone(WorkerAvailabilityService.IST_ZONE).toLocalTime().toString()
                            : "09:00 AM");

            ScheduledBookingRequest schReq = ScheduledBookingRequest.builder()
                    .serviceId(request.getServiceId())
                    .subServices(request.getSubServices())
                    .addons(request.getAddons())
                    .couponCode(request.getCouponCode())
                    .scheduledDate(date)
                    .timeSlot(timeSlot)
                    .userLat(request.getUserLat())
                    .userLng(request.getUserLng())
                    .paymentMethod(request.getPaymentMethod())
                    .pincode(request.getPincode())
                    .addressId(request.getAddressId())
                    .isForFriend(request.getIsForFriend())
                    .recipientName(request.getRecipientName())
                    .recipientPhone(request.getRecipientPhone())
                    .recipientAddress(request.getRecipientAddress())
                    .holdId(request.getHoldId())
                    .lockToken(request.getLockToken())
                    .build();
            return ResponseEntity.ok(scheduledBookingService.createScheduledBooking(userId, schReq, idempotencyKey));
        }

        InstantBookingRequest instReq = InstantBookingRequest.builder()
                .serviceId(request.getServiceId())
                .subServices(request.getSubServices())
                .addons(request.getAddons())
                .couponCode(request.getCouponCode())
                .userLat(request.getUserLat())
                .userLng(request.getUserLng())
                .paymentMethod(request.getPaymentMethod())
                .pincode(request.getPincode())
                .addressId(request.getAddressId())
                .isForFriend(request.getIsForFriend())
                .recipientName(request.getRecipientName())
                .recipientPhone(request.getRecipientPhone())
                .recipientAddress(request.getRecipientAddress())
                .build();
        return ResponseEntity.ok(instantBookingService.createInstantBooking(userId, instReq, idempotencyKey));
    }

    @GetMapping("/bookings/my-bookings")
    public ResponseEntity<List<BookingResponse>> getMyBookings(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "userId", required = false) UUID queryUserId) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);
        return ResponseEntity.ok(bookingStateMachine.getUserBookings(userId));
    }

    @GetMapping({"/bookings/time-slots", "/booking/slots"})
    public ResponseEntity<List<Map<String, Object>>> getLegacyAvailableTimeSlots(
            @RequestParam(value = "date", required = false) String dateStr) {
        LocalDate date = (dateStr != null && !dateStr.isBlank())
                ? LocalDate.parse(dateStr.trim())
                : LocalDate.now(WorkerAvailabilityService.IST_ZONE);

        List<AvailableSlotDto> dynamicSlots = availabilityService.getAvailableTimeSlots(date, null, null);
        if (!dynamicSlots.isEmpty()) {
            List<Map<String, Object>> resp = dynamicSlots.stream()
                    .map(s -> Map.<String, Object>of(
                            "time", s.getTime(),
                            "available", s.getAvailable(),
                            "date", date.toString()
                    ))
                    .toList();
            return ResponseEntity.ok(resp);
        }

        List<String> slotTimes = List.of(
                "09:00 AM - 10:00 AM", "10:00 AM - 11:00 AM", "11:00 AM - 12:00 PM",
                "12:00 PM - 01:00 PM", "02:00 PM - 03:00 PM", "03:00 PM - 04:00 PM",
                "04:00 PM - 05:00 PM", "05:00 PM - 06:00 PM", "06:00 PM - 07:00 PM"
        );
        java.time.ZonedDateTime nowIst = java.time.ZonedDateTime.now(WorkerAvailabilityService.IST_ZONE);
        boolean isToday = date.equals(nowIst.toLocalDate());
        LocalTime minAllowedTime = nowIst.toLocalTime().plusMinutes(30);

        List<Map<String, Object>> response = slotTimes.stream()
                .map(time -> {
                    LocalTime slotStart = parseLegacySlotStart(time);
                    boolean available = !isToday || (slotStart != null && slotStart.isAfter(minAllowedTime));
                    return Map.<String, Object>of(
                            "time", time,
                            "available", available,
                            "date", date.toString()
                    );
                })
                .toList();
        return ResponseEntity.ok(response);
    }

    private LocalTime parseLegacySlotStart(String timeSlot) {
        if (timeSlot == null || timeSlot.isBlank()) return LocalTime.of(9, 0);
        String startPart = timeSlot.contains("-") ? timeSlot.split("-")[0].trim() : timeSlot.trim();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d{1,2}):(\\d{2})\\s*(AM|PM)?", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(startPart);
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

    @GetMapping("/bookings/{id}")
    public ResponseEntity<BookingResponse> getBookingById(@PathVariable("id") UUID bookingId) {
        return ResponseEntity.ok(bookingStateMachine.getBookingById(bookingId));
    }

    @GetMapping("/bookings/{id}/history")
    public ResponseEntity<com.homeease.backend.dto.BookingEventDto.BookingHistoryPage> getBookingHistory(
            @PathVariable("id") UUID bookingId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Role", required = false) String userRole,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        UUID userId = resolveUserId(authHeader, headerUserId, null);
        boolean isAdmin = "ADMIN".equalsIgnoreCase(userRole);
        return ResponseEntity.ok(bookingStateMachine.getBookingHistory(bookingId, userId, isAdmin, page, size));
    }

    @PatchMapping("/bookings/{id}/status")
    public ResponseEntity<BookingResponse> updateBookingStatus(
            @PathVariable("id") UUID bookingId,
            @RequestParam("status") String status,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        UUID userId = resolveUserId(authHeader, headerUserId, null);
        if ("ACCEPTED".equalsIgnoreCase(status) || "CONFIRMED".equalsIgnoreCase(status)) {
            var booking = assignmentService.handleWorkerAcceptance(bookingId, userId);
            return ResponseEntity.ok(bookingStateMachine.mapToBookingResponse(booking));
        } else if ("COMPLETED".equalsIgnoreCase(status)) {
            var booking = bookingRepository.findById(bookingId).orElseThrow();
            sessionService.completeSession(booking, userId);
            return ResponseEntity.ok(bookingStateMachine.getBookingById(bookingId));
        } else if ("CANCELLED".equalsIgnoreCase(status)) {
            return ResponseEntity.ok(cancellationService.cancelBooking(bookingId, userId, "Status updated to CANCELLED"));
        }
        return ResponseEntity.ok(bookingStateMachine.getBookingById(bookingId));
    }

    @PostMapping("/bookings/{id}/cancel")
    public ResponseEntity<BookingResponse> cancelBooking(
            @PathVariable("id") UUID bookingId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @RequestParam(value = "reason", required = false) String reason,
            @RequestBody(required = false) CancelBookingRequest body) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);
        String finalReason = (body != null && body.getReason() != null) ? body.getReason() : reason;
        return ResponseEntity.ok(cancellationService.cancelBooking(bookingId, userId, finalReason));
    }

    // ─────────────────────────────────────────────────────────────────
    // WORKER EXECUTION & LIFECYCLE ENDPOINTS
    // ─────────────────────────────────────────────────────────────────

    @GetMapping({"/workers/me/jobs", "/workers/jobs"})
    public ResponseEntity<List<BookingResponse>> getWorkerJobs(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "userId", required = false) UUID queryUserId) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);
        Worker worker = workerRepository.findByUser_UserId(userId).orElse(null);
        if (worker == null) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        List<Booking> bookings = bookingRepository.findByWorker_WorkerId(worker.getWorkerId());
        return ResponseEntity.ok(bookings.stream().map(bookingStateMachine::mapToBookingResponse).toList());
    }

    @GetMapping({"/workers/me/requests", "/workers/bookings/requests", "/workers/requests"})
    public ResponseEntity<List<WorkerBookingRequestResponse>> getWorkerBookingRequests(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "userId", required = false) UUID queryUserId) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);
        return ResponseEntity.ok(assignmentService.getPendingRequestsForWorker(userId));
    }

    @PostMapping({"/bookings/{id}/accept", "/workers/bookings/{id}/accept"})
    public ResponseEntity<BookingResponse> acceptBooking(
            @PathVariable("id") UUID bookingId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "userId", required = false) UUID queryUserId) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);
        var booking = assignmentService.handleWorkerAcceptance(bookingId, userId);
        BookingResponse resp = bookingStateMachine.mapToBookingResponse(booking);
        resp.setPinCode(null); // Never expose customer PIN to worker
        return ResponseEntity.ok(resp);
    }

    @PostMapping({"/bookings/{id}/decline", "/bookings/{id}/reject", "/workers/bookings/{id}/reject"})
    public ResponseEntity<Map<String, String>> rejectBooking(
            @PathVariable("id") UUID bookingId,
            @RequestParam(value = "targetSubServiceId", required = false) UUID targetSubServiceId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "userId", required = false) UUID queryUserId) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);
        assignmentService.handleWorkerRejection(bookingId, userId, targetSubServiceId);
        return ResponseEntity.ok(Map.of(
                "status", "REJECTED",
                "message", "Booking request rejected."
        ));
    }

    @PostMapping({"/bookings/{id}/on-the-way", "/workers/bookings/{id}/on-the-way", "/workers/bookings/{id}/start-travel"})
    public ResponseEntity<BookingResponse> startTravel(
            @PathVariable("id") UUID bookingId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "userId", required = false) UUID queryUserId) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);
        var booking = assignmentService.handleWorkerStartTravel(bookingId, userId);
        BookingResponse resp = bookingStateMachine.mapToBookingResponse(booking);
        resp.setPinCode(null); // Never expose customer PIN to worker
        return ResponseEntity.ok(resp);
    }

    @PostMapping({"/bookings/{id}/arrive", "/workers/bookings/{id}/arrive"})
    public ResponseEntity<BookingResponse> arriveAtCustomer(
            @PathVariable("id") UUID bookingId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "userId", required = false) UUID queryUserId) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);
        var booking = assignmentService.handleWorkerArrival(bookingId, userId);
        BookingResponse resp = bookingStateMachine.mapToBookingResponse(booking);
        resp.setPinCode(null); // Never expose customer PIN to worker
        return ResponseEntity.ok(resp);
    }

    @PostMapping({"/bookings/{id}/verify-otp", "/bookings/{id}/verify-pin", "/workers/bookings/{id}/verify-pin"})
    public ResponseEntity<Map<String, Object>> verifyOtpAndStartService(
            @PathVariable("id") UUID bookingId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @RequestBody(required = false) Map<String, Object> body) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);

        String otp = null;
        if (body != null) {
            if (body.containsKey("otp")) otp = String.valueOf(body.get("otp"));
            else if (body.containsKey("otpCode")) otp = String.valueOf(body.get("otpCode"));
            else if (body.containsKey("pinCode")) otp = String.valueOf(body.get("pinCode"));
            else if (body.containsKey("pin")) otp = String.valueOf(body.get("pin"));
        }

        if (otp == null || otp.isBlank()) {
            throw new IllegalArgumentException("4-digit OTP is required in request body.");
        }

        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));

        // 1. Verify OTP
        bookingOtpService.verifyOtp(booking, userId, otp.trim());

        // 2. Start Service Session
        Worker worker = workerRepository.findByUser_UserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Worker not found for user: " + userId));

        var session = sessionService.startSession(booking, worker);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "SUCCESS");
        response.put("message", "OTP verified. Service session started.");
        response.put("bookingId", bookingId.toString());
        response.put("sessionId", session.getSessionId().toString());
        response.put("bookingStatus", BookingStage.IN_PROGRESS.name());
        response.put("startedAt", session.getStartedAt().toString());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/bookings/{id}/session")
    public ResponseEntity<Map<String, Object>> getServiceSession(@PathVariable("id") UUID bookingId) {
        return ResponseEntity.ok(sessionService.getSessionInfo(bookingId));
    }

    @PostMapping({"/bookings/{id}/complete", "/workers/bookings/{id}/complete"})
    public ResponseEntity<Map<String, Object>> completeJob(
            @PathVariable("id") UUID bookingId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "userId", required = false) UUID queryUserId) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));

        var session = sessionService.completeSession(booking, userId);

        // Phase 5: Automated Double-Entry Ledger Settlement (Rule #2)
        if (booking.getWorker() != null) {
            java.math.BigDecimal totalPrice = session.getFinalPrice() != null
                    ? session.getFinalPrice()
                    : (booking.getTotalAmount() != null ? booking.getTotalAmount() : java.math.BigDecimal.valueOf(499.0));
            java.math.BigDecimal workerShare = totalPrice.multiply(java.math.BigDecimal.valueOf(0.80)).setScale(2, java.math.RoundingMode.HALF_UP);
            java.math.BigDecimal platformFee = totalPrice.subtract(workerShare);
            String settlementKey = "settle-booking-" + booking.getBookingId();
            try {
                ledgerService.recordJobSettlement(booking, totalPrice, workerShare, platformFee, settlementKey);
            } catch (Exception e) {
                logger.warn("Automated ledger settlement for booking {} handled: {}", bookingId, e.getMessage());
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "SUCCESS");
        response.put("message", "Service completed successfully.");
        response.put("bookingId", bookingId.toString());
        response.put("sessionId", session.getSessionId().toString());
        response.put("bookingStatus", BookingStage.COMPLETED.name());
        response.put("completedAt", session.getCompletedAt().toString());
        response.put("durationMinutes", session.getDurationMinutes());
        response.put("finalPrice", session.getFinalPrice());
        return ResponseEntity.ok(response);
    }

    @PostMapping({"/bookings/{id}/retry-dispatch", "/bookings/{id}/retry-matching"})
    public ResponseEntity<Map<String, String>> retryDispatch(
            @PathVariable("id") UUID bookingId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "userId", required = false) UUID queryUserId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));

        UUID userId = null;
        try {
            userId = resolveUserId(authHeader, headerUserId, queryUserId);
        } catch (Exception ignored) {
            if (booking.getUser() != null) {
                userId = booking.getUser().getUserId();
            }
        }

        // Only retry if in a retryable state
        if (booking.getStatus() != BookingStage.WORKER_NOT_FOUND &&
            booking.getStatus() != BookingStage.SEARCHING &&
            booking.getStatus() != BookingStage.REQUESTED &&
            booking.getStatus() != BookingStage.PENDING) {
            Map<String, String> resp = new LinkedHashMap<>();
            resp.put("status", "SKIPPED");
            resp.put("bookingId", bookingId.toString());
            resp.put("message", "Booking is not in a retryable state: " + booking.getStatus());
            return ResponseEntity.ok(resp);
        }
        // Reset to REQUESTED and re-dispatch
        booking.setStatus(BookingStage.REQUESTED);
        bookingRepository.save(booking);

        UUID targetSubServiceId = null;
        List<com.homeease.backend.model.entity.BookingServiceItem> items =
                bookingServiceItemRepository.findByBooking_BookingId(bookingId);
        if (!items.isEmpty() && items.get(0).getSubService() != null) {
            targetSubServiceId = items.get(0).getSubService().getSubServiceId();
        }
        assignmentService.dispatchInstantBooking(booking, targetSubServiceId);

        Map<String, String> resp = new LinkedHashMap<>();
        resp.put("status", "CANDIDATES_ALERTED");
        resp.put("bookingId", bookingId.toString());
        resp.put("message", "Retry dispatch initiated for booking " + bookingId);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/bookings/{id}/events")
    public ResponseEntity<List<BookingEvent>> getBookingEvents(@PathVariable("id") UUID bookingId) {
        return ResponseEntity.ok(bookingEventRepository.findByBooking_BookingIdOrderByCreatedAtAsc(bookingId));
    }

    @PostMapping({"/workers/bookings/{id}/add-sub-service", "/bookings/{id}/line-items"})
    public ResponseEntity<BookingResponse> addExtraSubService(
            @PathVariable("id") UUID bookingId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @Valid @RequestBody AddExtraSubServiceRequest request) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);
        return ResponseEntity.ok(bookingStateMachine.addExtraSubService(bookingId, userId, request));
    }

    @PostMapping("/bookings/{id}/rating")
    public ResponseEntity<Map<String, Object>> rateBooking(
            @PathVariable("id") UUID bookingId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @Valid @RequestBody RatingRequest request) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));

        com.homeease.backend.model.entity.User user = booking.getUser();
        Worker worker = booking.getWorker();

        com.homeease.backend.model.entity.Review review = reviewRepository.findByBooking_BookingId(bookingId)
                .orElse(com.homeease.backend.model.entity.Review.builder()
                        .booking(booking)
                        .user(user)
                        .worker(worker)
                        .build());

        review.setRating(request.getRating());
        if (request.getComment() != null) {
            review.setComment(request.getComment());
        }
        reviewRepository.save(review);

        bookingEventRepository.save(com.homeease.backend.model.entity.BookingEvent.builder().booking(booking).eventType(com.homeease.backend.model.enums.BookingEventType.CUSTOMER_RATED).payload(String.format("{\"rating\":%d}", request.getRating())).deliveryStatus(com.homeease.backend.model.enums.EventDeliveryStatus.PENDING).build());


        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "SUCCESS");
        resp.put("message", "Rating submitted successfully.");
        resp.put("bookingId", bookingId.toString());
        resp.put("rating", request.getRating());
        return ResponseEntity.ok(resp);
    }
}
