package com.homeease.backend.controller;

import com.homeease.backend.dto.AuthDto.ApiResponse;
import com.homeease.backend.dto.WorkerDto.*;
import com.homeease.backend.exception.ResourceNotFoundException;
import com.homeease.backend.model.entity.Worker;
import com.homeease.backend.repository.WorkerRepository;
import com.homeease.backend.security.JwtService;
import com.homeease.backend.service.WorkerAvailabilityService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping({"/api/v1/workers", "/api/workers"})
@CrossOrigin(origins = "*")
public class WorkerAvailabilityController {

    private final WorkerAvailabilityService availabilityService;
    private final WorkerRepository workerRepository;
    private final JwtService jwtService;

    public WorkerAvailabilityController(WorkerAvailabilityService availabilityService,
                                        WorkerRepository workerRepository,
                                        JwtService jwtService) {
        this.availabilityService = availabilityService;
        this.workerRepository = workerRepository;
        this.jwtService = jwtService;
    }

    private UUID resolveWorkerId(String authHeader, UUID headerUserId, UUID queryUserId) {
        UUID userId = queryUserId != null ? queryUserId : headerUserId;
        if (userId == null && authHeader != null && !authHeader.isBlank()) {
            try {
                String clean = authHeader.replace("Bearer ", "").trim();
                userId = UUID.fromString(clean);
            } catch (Exception ignored) {
                userId = jwtService.extractUserId(authHeader);
            }
        }
        if (userId == null) {
            throw new ResourceNotFoundException("Missing user credentials. Provide X-User-Id header or Bearer token.");
        }
        final UUID finalUserId = userId;
        Worker worker = workerRepository.findByUser_UserId(finalUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Worker profile not found for user: " + finalUserId));
        return worker.getWorkerId();
    }

    /**
     * GET /api/v1/workers/availability
     * Fetch weekly working hours, break times, and days for worker.
     */
    @GetMapping("/availability")
    public ResponseEntity<ApiResponse<List<DayScheduleDto>>> getWeeklyAvailability(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestParam(value = "userId", required = false) UUID queryUserId) {
        UUID workerId = resolveWorkerId(authHeader, headerUserId, queryUserId);
        List<DayScheduleDto> schedule = availabilityService.getWorkerAvailability(workerId);
        return ResponseEntity.ok(ApiResponse.<List<DayScheduleDto>>builder()
                .status("SUCCESS")
                .data(schedule)
                .build());
    }

    /**
     * POST /api/v1/workers/availability
     * Update weekly working calendar, hours, and break times.
     */
    @PostMapping("/availability")
    public ResponseEntity<ApiResponse<List<DayScheduleDto>>> saveWeeklyAvailability(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @Valid @RequestBody SaveWeeklyAvailabilityRequest request) {
        UUID workerId = resolveWorkerId(authHeader, headerUserId, queryUserId);
        List<DayScheduleDto> updated = availabilityService.saveWorkerAvailability(workerId, request.getSchedule());
        return ResponseEntity.ok(ApiResponse.<List<DayScheduleDto>>builder()
                .status("SUCCESS")
                .message("Weekly availability updated successfully.")
                .data(updated)
                .build());
    }

    /**
     * GET /api/v1/workers/time-off
     * Get blocked dates / time off.
     */
    @GetMapping("/time-off")
    public ResponseEntity<ApiResponse<List<TimeOffResponse>>> getTimeOff(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestParam(value = "userId", required = false) UUID queryUserId) {
        UUID workerId = resolveWorkerId(authHeader, headerUserId, queryUserId);
        List<TimeOffResponse> timeOffList = availabilityService.getWorkerTimeOff(workerId);
        return ResponseEntity.ok(ApiResponse.<List<TimeOffResponse>>builder()
                .status("SUCCESS")
                .data(timeOffList)
                .build());
    }

    /**
     * POST /api/v1/workers/time-off
     * Block specific dates / request time off.
     */
    @PostMapping("/time-off")
    public ResponseEntity<ApiResponse<TimeOffResponse>> addTimeOff(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @Valid @RequestBody TimeOffRequest request) {
        UUID workerId = resolveWorkerId(authHeader, headerUserId, queryUserId);
        TimeOffResponse created = availabilityService.addWorkerTimeOff(workerId, request);
        return ResponseEntity.ok(ApiResponse.<TimeOffResponse>builder()
                .status("SUCCESS")
                .message("Time off recorded successfully.")
                .data(created)
                .build());
    }

    /**
     * DELETE /api/v1/workers/time-off/{id}
     * Unblock dates.
     */
    @DeleteMapping("/time-off/{id}")
    public ResponseEntity<ApiResponse<String>> deleteTimeOff(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @PathVariable("id") UUID timeOffId) {
        UUID workerId = resolveWorkerId(authHeader, headerUserId, queryUserId);
        availabilityService.deleteWorkerTimeOff(workerId, timeOffId);
        return ResponseEntity.ok(ApiResponse.<String>builder()
                .status("SUCCESS")
                .message("Time off deleted successfully.")
                .build());
    }

    /**
     * GET /api/v1/workers/schedule/today
     * Display today's work schedule.
     */
    @GetMapping("/schedule/today")
    public ResponseEntity<ApiResponse<WorkerTodayScheduleResponse>> getTodaySchedule(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestParam(value = "userId", required = false) UUID queryUserId) {
        UUID workerId = resolveWorkerId(authHeader, headerUserId, queryUserId);
        WorkerTodayScheduleResponse schedule = availabilityService.getTodaySchedule(workerId);
        return ResponseEntity.ok(ApiResponse.<WorkerTodayScheduleResponse>builder()
                .status("SUCCESS")
                .data(schedule)
                .build());
    }

    /**
     * GET /api/v1/workers/schedule/upcoming
     * View upcoming bookings.
     */
    @GetMapping("/schedule/upcoming")
    public ResponseEntity<ApiResponse<List<WorkerScheduleSlotDto>>> getUpcomingSchedule(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestParam(value = "userId", required = false) UUID queryUserId) {
        UUID workerId = resolveWorkerId(authHeader, headerUserId, queryUserId);
        List<WorkerScheduleSlotDto> upcoming = availabilityService.getUpcomingSchedule(workerId);
        return ResponseEntity.ok(ApiResponse.<List<WorkerScheduleSlotDto>>builder()
                .status("SUCCESS")
                .data(upcoming)
                .build());
    }
}
