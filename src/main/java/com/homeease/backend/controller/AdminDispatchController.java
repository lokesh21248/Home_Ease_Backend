package com.homeease.backend.controller;

import com.homeease.backend.model.entity.Booking;
import com.homeease.backend.model.entity.DispatchAuditLog;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.repository.BookingRepository;
import com.homeease.backend.repository.DispatchAuditLogRepository;
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

    public AdminDispatchController(DispatchAuditLogRepository dispatchAuditLogRepository,
                                   BookingRepository bookingRepository) {
        this.dispatchAuditLogRepository = dispatchAuditLogRepository;
        this.bookingRepository = bookingRepository;
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
}

