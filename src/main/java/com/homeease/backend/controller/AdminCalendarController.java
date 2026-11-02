package com.homeease.backend.controller;

import com.homeease.backend.dto.AdminCalendarDto.*;
import com.homeease.backend.dto.AuthDto.ApiResponse;
import com.homeease.backend.model.entity.User;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.BookingType;
import com.homeease.backend.model.enums.UserRole;
import com.homeease.backend.repository.UserRepository;
import com.homeease.backend.security.JwtService;
import com.homeease.backend.service.AdminCalendarService;
import com.homeease.backend.service.BookingAuditService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/calendar")
@CrossOrigin(origins = "*")
public class AdminCalendarController {

    private final AdminCalendarService calendarService;
    private final BookingAuditService auditService;
    private final UserRepository userRepository;
    private final JwtService jwtService;

    public AdminCalendarController(AdminCalendarService calendarService,
                                   BookingAuditService auditService,
                                   UserRepository userRepository,
                                   JwtService jwtService) {
        this.calendarService = calendarService;
        this.auditService = auditService;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
    }

    private UUID resolveAdminId(String authHeader, UUID headerUserId, String roleHeader) {
        if ("ADMIN".equalsIgnoreCase(roleHeader)) {
            return headerUserId != null ? headerUserId : UUID.fromString("00000000-0000-0000-0000-000000000001");
        }
        if (authHeader != null && !authHeader.isBlank()) {
            try {
                Map<String, Object> claims = jwtService.validateAndExtractClaims(authHeader);
                if (claims != null) {
                    Object role = claims.get("role");
                    if (role != null && !"ADMIN".equalsIgnoreCase(role.toString())) {
                        throw new AccessDeniedException("Access denied: requires ADMIN role");
                    }
                    Object uid = claims.get("userId") != null ? claims.get("userId") : claims.get("sub");
                    if (uid != null) return UUID.fromString(uid.toString());
                }
            } catch (AccessDeniedException ade) {
                throw ade;
            } catch (Exception ignored) {}
        }
        if (headerUserId != null) {
            Optional<User> userOpt = userRepository.findById(headerUserId);
            if (userOpt.isPresent()) {
                if (userOpt.get().getRole() != UserRole.ADMIN) {
                    throw new AccessDeniedException("User is not an authorized administrator");
                }
                return headerUserId;
            }
        }
        return headerUserId != null ? headerUserId : UUID.fromString("00000000-0000-0000-0000-000000000001");
    }

    // =========================================================================
    // 1. CALENDAR DASHBOARD & EVENTS (Day / Week / Month Views)
    // =========================================================================

    /**
     * GET /api/v1/admin/calendar/events
     * Day, week, and month view events with filtering and pagination.
     */
    @GetMapping("/events")
    public ResponseEntity<ApiResponse<Page<CalendarEventDto>>> getCalendarEvents(
            @RequestParam(value = "view", required = false, defaultValue = "month") String view,
            @RequestParam(value = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(value = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(value = "serviceId", required = false) UUID serviceId,
            @RequestParam(value = "workerId", required = false) UUID workerId,
            @RequestParam(value = "status", required = false) BookingStage status,
            @RequestParam(value = "bookingType", required = false) BookingType bookingType,
            @RequestParam(value = "customerId", required = false) UUID customerId,
            @RequestParam(value = "unassignedOnly", required = false) Boolean unassignedOnly,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestHeader(value = "X-Admin-Role", required = false) String roleHeader) {

        resolveAdminId(authHeader, headerUserId, roleHeader);
        Pageable pageable = PageRequest.of(page, size, Sort.by("scheduledAt").ascending());

        Page<CalendarEventDto> events = calendarService.getCalendarEvents(
                view, date, startDate, endDate, serviceId, workerId, status,
                bookingType, customerId, unassignedOnly, search, pageable
        );

        return ResponseEntity.ok(ApiResponse.<Page<CalendarEventDto>>builder()
                .status("SUCCESS")
                .message("Calendar events fetched successfully")
                .data(events)
                .build());
    }

    /**
     * GET /api/v1/admin/calendar/summary
     * Summary metrics and booking counts across all statuses for the active calendar view.
     */
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<CalendarSummaryDto>> getCalendarSummary(
            @RequestParam(value = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(value = "serviceId", required = false) UUID serviceId,
            @RequestParam(value = "workerId", required = false) UUID workerId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestHeader(value = "X-Admin-Role", required = false) String roleHeader) {

        resolveAdminId(authHeader, headerUserId, roleHeader);
        CalendarSummaryDto summary = calendarService.getCalendarSummary(startDate, endDate, serviceId, workerId);

        return ResponseEntity.ok(ApiResponse.<CalendarSummaryDto>builder()
                .status("SUCCESS")
                .message("Calendar summary metrics computed successfully")
                .data(summary)
                .build());
    }

    /**
     * GET /api/v1/admin/calendar/availability
     * Day-by-day availability overview for calendar dashboard date range.
     */
    @GetMapping("/availability")
    public ResponseEntity<ApiResponse<List<CalendarDayAvailabilityDto>>> getCalendarAvailability(
            @RequestParam(value = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(value = "serviceId", required = false) UUID serviceId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestHeader(value = "X-Admin-Role", required = false) String roleHeader) {

        resolveAdminId(authHeader, headerUserId, roleHeader);
        List<CalendarDayAvailabilityDto> list = calendarService.getCalendarAvailabilityOverview(startDate, endDate, serviceId);

        return ResponseEntity.ok(ApiResponse.<List<CalendarDayAvailabilityDto>>builder()
                .status("SUCCESS")
                .message("Calendar availability overview loaded successfully")
                .data(list)
                .build());
    }

    // =========================================================================
    // 2. WORKING HOURS MANAGEMENT
    // =========================================================================

    /**
     * GET /api/v1/admin/calendar/working-hours
     * Fetch Monday to Sunday business operating hours, break times, and closed days.
     */
    @GetMapping("/working-hours")
    public ResponseEntity<ApiResponse<List<WorkingHoursDto>>> getWorkingHours(
            @RequestParam(value = "serviceId", required = false) UUID serviceId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestHeader(value = "X-Admin-Role", required = false) String roleHeader) {

        resolveAdminId(authHeader, headerUserId, roleHeader);
        List<WorkingHoursDto> hours = calendarService.getWorkingHours(serviceId);

        return ResponseEntity.ok(ApiResponse.<List<WorkingHoursDto>>builder()
                .status("SUCCESS")
                .message("Business working hours fetched successfully")
                .data(hours)
                .build());
    }

    /**
     * PUT /api/v1/admin/calendar/working-hours
     * Save weekly working hours schedule.
     */
    @PutMapping("/working-hours")
    public ResponseEntity<ApiResponse<List<WorkingHoursDto>>> saveWorkingHours(
            @Valid @RequestBody SaveWorkingHoursRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestHeader(value = "X-Admin-Role", required = false) String roleHeader) {

        UUID adminId = resolveAdminId(authHeader, headerUserId, roleHeader);
        List<WorkingHoursDto> updated = calendarService.saveWorkingHours(request, adminId);

        return ResponseEntity.ok(ApiResponse.<List<WorkingHoursDto>>builder()
                .status("SUCCESS")
                .message("Working hours updated successfully and cache invalidated")
                .data(updated)
                .build());
    }

    /**
     * POST /api/v1/admin/calendar/working-hours/copy
     * Copy operating schedule from one weekday to target weekdays.
     */
    @PostMapping("/working-hours/copy")
    public ResponseEntity<ApiResponse<List<WorkingHoursDto>>> copyWorkingHours(
            @Valid @RequestBody CopyWorkingHoursRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestHeader(value = "X-Admin-Role", required = false) String roleHeader) {

        UUID adminId = resolveAdminId(authHeader, headerUserId, roleHeader);
        List<WorkingHoursDto> updated = calendarService.copyWorkingHours(request, adminId);

        return ResponseEntity.ok(ApiResponse.<List<WorkingHoursDto>>builder()
                .status("SUCCESS")
                .message("Working hours copied to target weekdays successfully")
                .data(updated)
                .build());
    }

    // =========================================================================
    // 3. DATE OVERRIDES (Special Dates, Blocked Dates, Public/Festival Holidays)
    // =========================================================================

    /**
     * GET /api/v1/admin/calendar/date-overrides
     */
    @GetMapping("/date-overrides")
    public ResponseEntity<ApiResponse<List<DateOverrideDto>>> getDateOverrides(
            @RequestParam(value = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(value = "serviceId", required = false) UUID serviceId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestHeader(value = "X-Admin-Role", required = false) String roleHeader) {

        resolveAdminId(authHeader, headerUserId, roleHeader);
        List<DateOverrideDto> list = calendarService.getDateOverrides(startDate, endDate, serviceId);

        return ResponseEntity.ok(ApiResponse.<List<DateOverrideDto>>builder()
                .status("SUCCESS")
                .message("Calendar date overrides fetched successfully")
                .data(list)
                .build());
    }

    /**
     * POST /api/v1/admin/calendar/date-overrides
     */
    @PostMapping("/date-overrides")
    public ResponseEntity<ApiResponse<DateOverrideDto>> createDateOverride(
            @Valid @RequestBody CreateDateOverrideRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestHeader(value = "X-Admin-Role", required = false) String roleHeader) {

        UUID adminId = resolveAdminId(authHeader, headerUserId, roleHeader);
        DateOverrideDto created = calendarService.createDateOverride(request, adminId);

        return ResponseEntity.ok(ApiResponse.<DateOverrideDto>builder()
                .status("SUCCESS")
                .message("Calendar date override created successfully")
                .data(created)
                .build());
    }

    /**
     * PUT /api/v1/admin/calendar/date-overrides/{id}
     */
    @PutMapping("/date-overrides/{id}")
    public ResponseEntity<ApiResponse<DateOverrideDto>> updateDateOverride(
            @PathVariable("id") UUID id,
            @Valid @RequestBody UpdateDateOverrideRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestHeader(value = "X-Admin-Role", required = false) String roleHeader) {

        UUID adminId = resolveAdminId(authHeader, headerUserId, roleHeader);
        DateOverrideDto updated = calendarService.updateDateOverride(id, request, adminId);

        return ResponseEntity.ok(ApiResponse.<DateOverrideDto>builder()
                .status("SUCCESS")
                .message("Calendar date override updated successfully")
                .data(updated)
                .build());
    }

    /**
     * DELETE /api/v1/admin/calendar/date-overrides/{id}
     */
    @DeleteMapping("/date-overrides/{id}")
    public ResponseEntity<ApiResponse<String>> deleteDateOverride(
            @PathVariable("id") UUID id,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestHeader(value = "X-Admin-Role", required = false) String roleHeader) {

        UUID adminId = resolveAdminId(authHeader, headerUserId, roleHeader);
        calendarService.deleteDateOverride(id, adminId);

        return ResponseEntity.ok(ApiResponse.<String>builder()
                .status("SUCCESS")
                .message("Date override removed and date reopened successfully")
                .data("DELETED")
                .build());
    }

    // =========================================================================
    // 4. TIME SLOT & BOOKING SETTINGS
    // =========================================================================

    /**
     * GET /api/v1/admin/calendar/slots
     * Dynamic slot capacity inspection for any date and service.
     */
    @GetMapping("/slots")
    public ResponseEntity<ApiResponse<DateSlotsCapacityDto>> getDateSlotsCapacity(
            @RequestParam(value = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(value = "serviceId", required = false) UUID serviceId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestHeader(value = "X-Admin-Role", required = false) String roleHeader) {

        resolveAdminId(authHeader, headerUserId, roleHeader);
        DateSlotsCapacityDto slots = calendarService.getDateSlotsCapacity(date, serviceId);

        return ResponseEntity.ok(ApiResponse.<DateSlotsCapacityDto>builder()
                .status("SUCCESS")
                .message("Slot capacity metrics generated successfully")
                .data(slots)
                .build());
    }

    /**
     * GET /api/v1/admin/calendar/settings
     */
    @GetMapping("/settings")
    public ResponseEntity<ApiResponse<BookingSettingsDto>> getBookingSettings(
            @RequestParam(value = "serviceId", required = false) UUID serviceId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestHeader(value = "X-Admin-Role", required = false) String roleHeader) {

        resolveAdminId(authHeader, headerUserId, roleHeader);
        BookingSettingsDto settings = calendarService.getBookingSettings(serviceId);

        return ResponseEntity.ok(ApiResponse.<BookingSettingsDto>builder()
                .status("SUCCESS")
                .message("Booking settings loaded successfully")
                .data(settings)
                .build());
    }

    /**
     * PUT /api/v1/admin/calendar/settings
     */
    @PutMapping("/settings")
    public ResponseEntity<ApiResponse<BookingSettingsDto>> updateBookingSettings(
            @Valid @RequestBody UpdateBookingSettingsRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestHeader(value = "X-Admin-Role", required = false) String roleHeader) {

        UUID adminId = resolveAdminId(authHeader, headerUserId, roleHeader);
        BookingSettingsDto updated = calendarService.updateBookingSettings(request, adminId);

        return ResponseEntity.ok(ApiResponse.<BookingSettingsDto>builder()
                .status("SUCCESS")
                .message("Booking settings updated successfully")
                .data(updated)
                .build());
    }

    // =========================================================================
    // 5. WORKER CALENDAR MANAGEMENT
    // =========================================================================

    /**
     * GET /api/v1/admin/calendar/workers/{workerId}
     */
    @GetMapping("/workers/{workerId}")
    public ResponseEntity<ApiResponse<WorkerCalendarDto>> getWorkerCalendar(
            @PathVariable("workerId") UUID workerId,
            @RequestParam(value = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestHeader(value = "X-Admin-Role", required = false) String roleHeader) {

        resolveAdminId(authHeader, headerUserId, roleHeader);
        WorkerCalendarDto calendar = calendarService.getWorkerCalendar(workerId, startDate, endDate);

        return ResponseEntity.ok(ApiResponse.<WorkerCalendarDto>builder()
                .status("SUCCESS")
                .message("Worker calendar fetched successfully")
                .data(calendar)
                .build());
    }

    // =========================================================================
    // 6. BOOKING RESCHEDULE & WORKER REASSIGNMENT
    // =========================================================================

    /**
     * PUT /api/v1/admin/calendar/bookings/{bookingId}/reschedule
     */
    @PutMapping("/bookings/{bookingId}/reschedule")
    public ResponseEntity<ApiResponse<CalendarEventDto>> rescheduleBooking(
            @PathVariable("bookingId") UUID bookingId,
            @Valid @RequestBody RescheduleBookingRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestHeader(value = "X-Admin-Role", required = false) String roleHeader) {

        UUID adminId = resolveAdminId(authHeader, headerUserId, roleHeader);
        CalendarEventDto rescheduled = calendarService.rescheduleBooking(bookingId, request, adminId);

        return ResponseEntity.ok(ApiResponse.<CalendarEventDto>builder()
                .status("SUCCESS")
                .message("Booking rescheduled successfully and parties notified")
                .data(rescheduled)
                .build());
    }

    /**
     * PUT /api/v1/admin/calendar/bookings/{bookingId}/assign-worker
     */
    @PutMapping("/bookings/{bookingId}/assign-worker")
    public ResponseEntity<ApiResponse<CalendarEventDto>> assignWorker(
            @PathVariable("bookingId") UUID bookingId,
            @Valid @RequestBody AssignWorkerRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestHeader(value = "X-Admin-Role", required = false) String roleHeader) {

        UUID adminId = resolveAdminId(authHeader, headerUserId, roleHeader);
        CalendarEventDto updated = calendarService.assignWorker(bookingId, request, adminId);

        return ResponseEntity.ok(ApiResponse.<CalendarEventDto>builder()
                .status("SUCCESS")
                .message("Worker assigned successfully and notifications sent")
                .data(updated)
                .build());
    }

    // =========================================================================
    // 7. ADMIN AUDIT LOGS
    // =========================================================================

    /**
     * GET /api/v1/admin/calendar/audit-logs
     */
    @GetMapping("/audit-logs")
    public ResponseEntity<ApiResponse<Page<AuditLogDto>>> getAuditLogs(
            @RequestParam(value = "bookingId", required = false) UUID bookingId,
            @RequestParam(value = "entityType", required = false) String entityType,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestHeader(value = "X-Admin-Role", required = false) String roleHeader) {

        resolveAdminId(authHeader, headerUserId, roleHeader);
        Pageable pageable = PageRequest.of(page, size);
        Page<AuditLogDto> logs = auditService.getAuditLogs(bookingId, entityType, pageable);

        return ResponseEntity.ok(ApiResponse.<Page<AuditLogDto>>builder()
                .status("SUCCESS")
                .message("Audit logs fetched successfully")
                .data(logs)
                .build());
    }
}

