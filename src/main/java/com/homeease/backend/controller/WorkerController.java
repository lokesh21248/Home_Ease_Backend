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

    private UUID resolveUserId(String authHeader, UUID headerUserId, UUID directUserId) {
        if (directUserId != null) return directUserId;
        if (headerUserId != null) return headerUserId;
        if (authHeader != null && !authHeader.isBlank()) {
            try {
                String clean = authHeader.replace("Bearer ", "").trim();
                return UUID.fromString(clean);
            } catch (Exception ignored) {
                UUID fromJwt = jwtService.extractUserId(authHeader);
                if (fromJwt != null) return fromJwt;
            }
        }
        throw new ResourceNotFoundException("Missing userId. Please provide userId in the request body, query parameter (?userId=...), or X-User-Id header.");
    }

    /**
     * GET /api/v1/workers/profile
     * Can pass ?userId=... OR header X-User-Id OR Authorization
     */
    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<WorkerProfileResponse>> getWorkerProfile(
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
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
     * Can pass userId in body OR ?userId=... OR X-User-Id header OR Authorization
     */
    @PostMapping("/register-kyc")
    public ResponseEntity<ApiResponse<KycSubmissionResponse>> registerKyc(
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
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
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody StatusToggleRequest request) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);
        WorkerProfileResponse profile = workerService.toggleOnlineStatus(userId, request.getIsOnline());
        return ResponseEntity.ok(ApiResponse.<WorkerProfileResponse>builder()
                .status("SUCCESS")
                .data(profile)
                .build());
    }

    @PostMapping("/location")
    public ResponseEntity<ApiResponse<String>> updateLiveLocation(
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody LocationUpdateRequest request) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);
        workerService.updateLiveLocation(userId, request.getLat(), request.getLng());
        return ResponseEntity.ok(ApiResponse.<String>builder()
                .status("SUCCESS")
                .message("Worker live location updated successfully.")
                .build());
    }

    @GetMapping("/earnings")
    public ResponseEntity<ApiResponse<EarningsResponse>> getWorkerEarnings(
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);
        EarningsResponse earnings = workerService.getWorkerEarnings(userId);
        return ResponseEntity.ok(ApiResponse.<EarningsResponse>builder()
                .status("SUCCESS")
                .data(earnings)
                .build());
    }
}
