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
@RequestMapping("/api/v1/workers")
@CrossOrigin(origins = "*")
public class WorkerController {

    private final WorkerService workerService;
    private final JwtService jwtService;

    public WorkerController(WorkerService workerService, JwtService jwtService) {
        this.workerService = workerService;
        this.jwtService = jwtService;
    }

    private UUID resolveUserId(String authHeader, UUID headerUserId) {
        if (headerUserId != null) return headerUserId;
        if (authHeader != null && !authHeader.isBlank()) {
            return jwtService.extractUserId(authHeader);
        }
        throw new ResourceNotFoundException("Missing authentication. Please provide Authorization Bearer token or X-User-Id.");
    }

    /**
     * GET /api/v1/workers/profile
     * Header: Authorization: Bearer <JWT_TOKEN>
     */
    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<WorkerProfileResponse>> getWorkerProfile(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId) {
        UUID userId = resolveUserId(authHeader, headerUserId);
        WorkerProfileResponse profile = workerService.getWorkerProfile(userId);
        return ResponseEntity.ok(ApiResponse.<WorkerProfileResponse>builder()
                .status("SUCCESS")
                .data(profile)
                .build());
    }

    /**
     * POST /api/v1/workers/register-kyc
     * Header: Authorization: Bearer <JWT_TOKEN>
     */
    @PostMapping("/register-kyc")
    public ResponseEntity<ApiResponse<KycSubmissionResponse>> registerKyc(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @Valid @RequestBody KycRegisterRequest request) {
        UUID userId = resolveUserId(authHeader, headerUserId);
        KycSubmissionResponse response = workerService.registerKyc(userId, request);
        return ResponseEntity.ok(ApiResponse.<KycSubmissionResponse>builder()
                .status("SUCCESS")
                .message("KYC submitted successfully. Verification in progress.")
                .data(response)
                .build());
    }

    @PostMapping("/status")
    public ResponseEntity<ApiResponse<WorkerProfileResponse>> toggleOnlineStatus(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @Valid @RequestBody StatusToggleRequest request) {
        UUID userId = resolveUserId(authHeader, headerUserId);
        WorkerProfileResponse profile = workerService.toggleOnlineStatus(userId, request.getIsOnline());
        return ResponseEntity.ok(ApiResponse.<WorkerProfileResponse>builder()
                .status("SUCCESS")
                .data(profile)
                .build());
    }

    @PostMapping("/location")
    public ResponseEntity<ApiResponse<String>> updateLiveLocation(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @Valid @RequestBody LocationUpdateRequest request) {
        UUID userId = resolveUserId(authHeader, headerUserId);
        workerService.updateLiveLocation(userId, request.getLat(), request.getLng());
        return ResponseEntity.ok(ApiResponse.<String>builder()
                .status("SUCCESS")
                .message("Worker live location updated successfully.")
                .build());
    }

    @GetMapping("/earnings")
    public ResponseEntity<ApiResponse<EarningsResponse>> getWorkerEarnings(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId) {
        UUID userId = resolveUserId(authHeader, headerUserId);
        EarningsResponse earnings = workerService.getWorkerEarnings(userId);
        return ResponseEntity.ok(ApiResponse.<EarningsResponse>builder()
                .status("SUCCESS")
                .data(earnings)
                .build());
    }
}
