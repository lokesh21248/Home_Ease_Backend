package com.homeease.backend.controller;

import com.homeease.backend.dto.WorkerDto.*;
import com.homeease.backend.service.WorkerService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workers")
public class WorkerController {

    private final WorkerService workerService;

    public WorkerController(WorkerService workerService) {
        this.workerService = workerService;
    }

    @GetMapping("/profile")
    public ResponseEntity<WorkerProfileResponse> getWorkerProfile(
            @RequestHeader("X-User-Id") UUID userId) {
        return ResponseEntity.ok(workerService.getWorkerProfile(userId));
    }

    @PostMapping("/register-kyc")
    public ResponseEntity<WorkerProfileResponse> registerKyc(
            @RequestHeader("X-User-Id") UUID userId,
            @Valid @RequestBody KycRegisterRequest request) {
        return ResponseEntity.ok(workerService.registerKyc(userId, request));
    }

    @PostMapping("/status")
    public ResponseEntity<WorkerProfileResponse> toggleOnlineStatus(
            @RequestHeader("X-User-Id") UUID userId,
            @Valid @RequestBody StatusToggleRequest request) {
        return ResponseEntity.ok(workerService.toggleOnlineStatus(userId, request.getIsOnline()));
    }

    @PostMapping("/location")
    public ResponseEntity<String> updateLiveLocation(
            @RequestHeader("X-User-Id") UUID userId,
            @Valid @RequestBody LocationUpdateRequest request) {
        workerService.updateLiveLocation(userId, request.getLat(), request.getLng());
        return ResponseEntity.ok("Worker live location updated successfully.");
    }

    @GetMapping("/earnings")
    public ResponseEntity<EarningsResponse> getWorkerEarnings(@RequestHeader("X-User-Id") UUID userId) {
        return ResponseEntity.ok(workerService.getWorkerEarnings(userId));
    }
}
