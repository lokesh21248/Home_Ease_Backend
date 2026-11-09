package com.homeease.backend.controller;

import com.homeease.backend.model.entity.Booking;
import com.homeease.backend.model.entity.BookingAssignment;
import com.homeease.backend.model.entity.DispatchAuditLog;
import com.homeease.backend.model.enums.AssignmentStatus;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.repository.BookingAssignmentRepository;
import com.homeease.backend.repository.BookingRepository;
import com.homeease.backend.repository.DispatchAuditLogRepository;
import com.homeease.backend.service.WaveDispatchService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping({"/api/v1/admin/dispatch", "/api/admin/dispatch"})
@CrossOrigin(origins = "*")
public class AdminDispatchController {

    private final DispatchAuditLogRepository dispatchAuditLogRepository;
    private final BookingRepository bookingRepository;
    private final BookingAssignmentRepository bookingAssignmentRepository;
    private final WaveDispatchService waveDispatchService;

    public AdminDispatchController(DispatchAuditLogRepository dispatchAuditLogRepository,
                                   BookingRepository bookingRepository,
                                   BookingAssignmentRepository bookingAssignmentRepository,
                                   WaveDispatchService waveDispatchService) {
        this.dispatchAuditLogRepository = dispatchAuditLogRepository;
        this.bookingRepository = bookingRepository;
        this.bookingAssignmentRepository = bookingAssignmentRepository;
        this.waveDispatchService = waveDispatchService;
    }

    /**
     * GET /api/v1/admin/dispatch/logs
     * Returns dispatch logs with audit trail without customer PII.
     */
    @GetMapping("/logs")
    public ResponseEntity<Map<String, Object>> getDispatchLogs(
            @RequestParam(value = "bookingId", required = false) UUID bookingId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        Page<DispatchAuditLog> logPage;

        if (bookingId != null) {
            logPage = dispatchAuditLogRepository.findByBooking_BookingIdOrderByCreatedAtDesc(bookingId, pageable);
        } else {
            logPage = dispatchAuditLogRepository.findAllByOrderByCreatedAtDesc(pageable);
        }

        List<Map<String, Object>> items = logPage.getContent().stream().map(log -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("logId", log.getLogId());
            map.put("bookingId", log.getBooking() != null ? log.getBooking().getBookingId() : null);
            map.put("serviceId", log.getServiceId());
            map.put("customerLat", log.getCustomerLat());
            map.put("customerLng", log.getCustomerLng());
            map.put("searchRadiusMeters", log.getSearchRadiusMeters());
            map.put("eligibleWorkersCount", log.getEligibleWorkersCount());
            map.put("rejectedWorkerReasons", log.getRejectedWorkerReasons());
            map.put("selectedWorkerId", log.getSelectedWorkerId());
            map.put("kafkaStatus", log.getKafkaStatus());
            map.put("websocketStatus", log.getWebsocketStatus());
            map.put("fcmStatus", log.getFcmStatus());
            map.put("assignmentStatus", log.getAssignmentStatus());
            map.put("createdAt", log.getCreatedAt());
            return map;
        }).toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("logs", items);
        response.put("totalElements", logPage.getTotalElements());
        response.put("totalPages", logPage.getTotalPages());
        response.put("currentPage", logPage.getNumber());
        response.put("pageSize", logPage.getSize());

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/admin/dispatch/failed-bookings
     * Returns bookings where dispatch failed to find eligible workers.
     */
    @GetMapping("/failed-bookings")
    public ResponseEntity<List<Map<String, Object>>> getFailedBookings() {
        List<Booking> failedBookings = bookingRepository.findByStatus(BookingStage.WORKER_NOT_FOUND);

        List<Map<String, Object>> result = failedBookings.stream().map(b -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("bookingId", b.getBookingId());
            map.put("serviceId", b.getService() != null ? b.getService().getServiceId() : null);
            map.put("serviceName", b.getService() != null ? b.getService().getName() : "Home Service");
            map.put("customerLat", b.getUserLat());
            map.put("customerLng", b.getUserLng());
            map.put("status", b.getStatus().name());
            map.put("createdAt", b.getCreatedAt());
            return map;
        }).toList();

        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/v1/admin/dispatch/history
     * Returns recent dispatch metrics and summary.
     */
    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> getDispatchHistory() {
        Page<DispatchAuditLog> recent = dispatchAuditLogRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 10));

        long totalFailed = bookingRepository.findByStatus(BookingStage.WORKER_NOT_FOUND).size();
        long totalSearching = bookingRepository.findByStatus(BookingStage.SEARCHING).size();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("recentLogsCount", recent.getTotalElements());
        summary.put("currentlySearchingCount", totalSearching);
        summary.put("failedBookingsCount", totalFailed);
        summary.put("recentLogs", recent.getContent());

        return ResponseEntity.ok(summary);
    }

    /**
     * GET /api/v1/admin/dispatch/active-waves
     * Returns real-time list of bookings currently in SEARCHING stage with wave progression details.
     */
    @GetMapping("/active-waves")
    public ResponseEntity<List<Map<String, Object>>> getActiveWaves() {
        List<Booking> searching = bookingRepository.findByStatus(BookingStage.SEARCHING);
        List<Map<String, Object>> result = new ArrayList<>();
        Instant now = Instant.now();

        for (Booking b : searching) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("bookingId", b.getBookingId());
            map.put("serviceId", b.getService() != null ? b.getService().getServiceId() : null);
            map.put("serviceName", b.getService() != null ? b.getService().getName() : "Home Service");
            map.put("customerLat", b.getUserLat());
            map.put("customerLng", b.getUserLng());
            map.put("pincode", b.getServicePincode());
            map.put("totalAmount", b.getTotalAmount());
            map.put("createdAt", b.getCreatedAt());

            List<BookingAssignment> assignments = bookingAssignmentRepository.findByBooking_BookingId(b.getBookingId());
            int currentRound = assignments.stream().mapToInt(BookingAssignment::getAttemptNo).max().orElse(1);
            long pendingOffers = assignments.stream().filter(a -> a.getStatus() == AssignmentStatus.PENDING).count();

            Optional<Instant> earliestExpiry = assignments.stream()
                    .filter(a -> a.getStatus() == AssignmentStatus.PENDING && a.getExpiresAt() != null)
                    .map(BookingAssignment::getExpiresAt)
                    .min(Comparator.naturalOrder());

            long secondsRemaining = earliestExpiry
                    .map(exp -> Math.max(0, java.time.Duration.between(now, exp).getSeconds()))
                    .orElse(20L);

            map.put("currentRound", currentRound);
            map.put("currentRadiusMeters", currentRound <= 3 ? WaveDispatchService.DISPATCH_ROUNDS_METERS[currentRound - 1] : 8000.0);
            map.put("pendingOffersCount", pendingOffers);
            map.put("totalAttempts", assignments.size());
            map.put("secondsRemaining", secondsRemaining);

            result.add(map);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/v1/admin/dispatch/retry/{bookingId}
     * Ops manager intervention: triggers fresh multi-wave dispatch.
     */
    @PostMapping("/retry/{bookingId}")
    public ResponseEntity<Map<String, Object>> retryDispatch(@PathVariable UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new NoSuchElementException("Booking not found: " + bookingId));

        booking.setStatus(BookingStage.SEARCHING);
        bookingRepository.save(booking);

        waveDispatchService.startInstantWaveDispatch(booking, null);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", "Wave dispatch re-triggered for booking #" + bookingId);
        resp.put("bookingId", bookingId);
        return ResponseEntity.ok(resp);
    }

    /**
     * GET /api/v1/admin/dispatch/candidates/{bookingId}
     * Returns candidate assignments and scoring evaluation history.
     */
    @GetMapping("/candidates/{bookingId}")
    public ResponseEntity<List<Map<String, Object>>> getCandidates(@PathVariable UUID bookingId) {
        List<BookingAssignment> assignments = bookingAssignmentRepository.findByBooking_BookingId(bookingId);
        List<Map<String, Object>> list = assignments.stream().map(a -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("assignmentId", a.getAssignmentId());
            map.put("workerId", a.getWorker() != null ? a.getWorker().getWorkerId() : null);

            String workerName = "Partner";
            String workerPhone = "";
            if (a.getWorker() != null && a.getWorker().getUser() != null) {
                if (a.getWorker().getUser().getFullName() != null) {
                    workerName = a.getWorker().getUser().getFullName();
                }
                if (a.getWorker().getUser().getPhoneNumber() != null) {
                    workerPhone = a.getWorker().getUser().getPhoneNumber();
                }
            }

            map.put("workerName", workerName);
            map.put("workerPhone", workerPhone);
            map.put("attemptNo", a.getAttemptNo());
            map.put("score", a.getScore());
            map.put("rankOrder", a.getRankOrder());
            map.put("status", a.getStatus().name());
            map.put("requestedAt", a.getRequestedAt());
            map.put("expiresAt", a.getExpiresAt());
            map.put("respondedAt", a.getRespondedAt());
            return map;
        }).toList();

        return ResponseEntity.ok(list);
    }

    /**
     * POST /api/v1/admin/dispatch/cancel/{bookingId}
     * Admin terminates matching for a booking.
     */
    @PostMapping("/cancel/{bookingId}")
    public ResponseEntity<Map<String, Object>> cancelDispatch(
            @PathVariable UUID bookingId,
            @RequestBody(required = false) Map<String, String> body) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new NoSuchElementException("Booking not found: " + bookingId));

        booking.setStatus(BookingStage.CANCELLED);
        booking.setCancellationReason(body != null && body.containsKey("reason") ? body.get("reason") : "Cancelled by ops admin");
        bookingRepository.save(booking);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", "Booking dispatch cancelled");
        return ResponseEntity.ok(resp);
    }
}

