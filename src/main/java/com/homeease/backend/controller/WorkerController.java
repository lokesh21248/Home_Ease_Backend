package com.homeease.backend.controller;

import com.homeease.backend.dto.AuthDto.ApiResponse;
import com.homeease.backend.dto.WorkerDto.*;
import com.homeease.backend.exception.ResourceNotFoundException;
import com.homeease.backend.security.JwtService;
import com.homeease.backend.service.WorkerService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping({"/api/v1/workers", "/api/workers"})
@CrossOrigin(origins = "*")
public class WorkerController {

    private final WorkerService workerService;
    private final JwtService jwtService;

    public WorkerController(WorkerService workerService, JwtService jwtService) {
        this.workerService = workerService;
        this.jwtService = jwtService;
    }

    private UUID resolveUserId(String authHeader, String headerUserIdStr, UUID directUserId) {
        return com.homeease.backend.security.SecurityUtils.resolveAuthenticatedUserId(directUserId, headerUserIdStr);
    }

    /**
     * GET /api/v1/workers/profile
     */
    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<WorkerProfileResponse>> getWorkerProfile(
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);
        WorkerProfileResponse profile = workerService.getWorkerProfile(userId);
        return ResponseEntity.ok(ApiResponse.<WorkerProfileResponse>builder()
                .status("SUCCESS")
                .data(profile)
                .build());
    }

    /**
     * POST /api/v1/workers/register-kyc
     */
    @PostMapping("/register-kyc")
    public ResponseEntity<ApiResponse<KycSubmissionResponse>> registerKyc(
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody KycRegisterRequest request) {
        UUID directId = request.getUserId() != null ? request.getUserId() : queryUserId;
        UUID userId = resolveUserId(authHeader, headerUserId, directId);
        KycSubmissionResponse response = workerService.registerKyc(userId, request);
        return ResponseEntity.ok(ApiResponse.<KycSubmissionResponse>builder()
                .status("SUCCESS")
                .message("KYC submitted successfully. Verification in progress.")
                .data(response)
                .build());
    }

    @PostMapping("/status")
    public ResponseEntity<ApiResponse<WorkerProfileResponse>> toggleOnlineStatus(
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody StatusToggleRequest request) {
        UUID directId = request.getUserId() != null ? request.getUserId() : queryUserId;
        UUID userId = resolveUserId(authHeader, headerUserId, directId);
        WorkerProfileResponse profile = workerService.toggleOnlineStatus(userId, request.getIsOnline(), request.getLat(), request.getLng());
        return ResponseEntity.ok(ApiResponse.<WorkerProfileResponse>builder()
                .status("SUCCESS")
                .data(profile)
                .build());
    }

    @PostMapping({"/services", "/profile/services"})
    public ResponseEntity<ApiResponse<WorkerProfileResponse>> updateWorkerServices(
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody UpdateWorkerServicesRequest request) {
        UUID directId = request != null && request.getUserId() != null ? request.getUserId() : queryUserId;
        UUID userId = resolveUserId(authHeader, headerUserId, directId);
        java.util.List<String> serviceIds = new java.util.ArrayList<>();
        String workingPincodes = null;
        if (request != null) {
            if (request.getServiceIds() != null) serviceIds.addAll(request.getServiceIds());
            if (request.getSubServiceIds() != null) serviceIds.addAll(request.getSubServiceIds());
            workingPincodes = request.getWorkingPincodes();
        }
        WorkerProfileResponse profile = workerService.updateWorkerServices(userId, serviceIds, workingPincodes);
        return ResponseEntity.ok(ApiResponse.<WorkerProfileResponse>builder()
                .status("SUCCESS")
                .message("Worker services updated successfully.")
                .data(profile)
                .build());
    }

    @PostMapping("/location")
    public ResponseEntity<ApiResponse<String>> updateLiveLocation(
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody LocationUpdateRequest request) {
        UUID directId = queryUserId;
        UUID userId = resolveUserId(authHeader, headerUserId, directId);
        workerService.updateLiveLocation(userId, request.getLat(), request.getLng());
        return ResponseEntity.ok(ApiResponse.<String>builder()
                .status("SUCCESS")
                .message("Worker live location updated successfully.")
                .build());
    }

    @GetMapping("/earnings")
    public ResponseEntity<ApiResponse<EarningsResponse>> getWorkerEarnings(
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);
        EarningsResponse earnings = workerService.getWorkerEarnings(userId);
        return ResponseEntity.ok(ApiResponse.<EarningsResponse>builder()
                .status("SUCCESS")
                .data(earnings)
                .build());
    }
}
