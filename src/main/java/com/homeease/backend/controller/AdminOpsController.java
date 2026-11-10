package com.homeease.backend.controller;

import com.homeease.backend.dto.AdminOpsDto.*;
import com.homeease.backend.model.enums.DisputeStatus;
import com.homeease.backend.service.AdminOpsService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping({"/api/v1/admin/ops", "/api/admin/ops"})
@PreAuthorize("hasRole('ADMIN')")
@CrossOrigin(origins = "*")
public class AdminOpsController {

    private final AdminOpsService adminOpsService;

    public AdminOpsController(AdminOpsService adminOpsService) {
        this.adminOpsService = adminOpsService;
    }

    // ─────────────────────────────────────────────────────────────────
    // 1. ZONE & DISPATCH CONFIG CONTROL (Rule #10)
    // ─────────────────────────────────────────────────────────────────

    @GetMapping("/zones")
    public ResponseEntity<List<ZoneConfigResponse>> getAllZoneConfigs() {
        return ResponseEntity.ok(adminOpsService.getAllZoneConfigs());
    }

    @PostMapping("/zones/{configId}/toggle")
    public ResponseEntity<ZoneConfigResponse> toggleZoneActive(
            @PathVariable("configId") UUID configId,
            @RequestParam("active") boolean active) {
        return ResponseEntity.ok(adminOpsService.toggleZoneActive(configId, active));
    }

    @PutMapping("/zones/{configId}/config")
    public ResponseEntity<ZoneConfigResponse> updateZoneConfig(
            @PathVariable("configId") UUID configId,
            @RequestBody UpdateZoneConfigRequest request) {
        return ResponseEntity.ok(adminOpsService.updateZoneConfig(configId, request));
    }

    // ─────────────────────────────────────────────────────────────────
    // 2. MANUAL FORCE REASSIGNMENT (Rule #3 & Rule #8)
    // ─────────────────────────────────────────────────────────────────

    @PostMapping("/bookings/{bookingId}/force-reassign")
    public ResponseEntity<Map<String, Object>> forceReassignBooking(
            @PathVariable("bookingId") UUID bookingId,
            @Valid @RequestBody ForceReassignRequest request,
            @RequestHeader(value = "X-Admin-Id", required = false) UUID adminId) {
        UUID effectiveAdminId = adminId != null ? adminId : UUID.fromString("00000000-0000-0000-0000-000000000001");
        return ResponseEntity.ok(adminOpsService.forceReassignBooking(
                bookingId, request.getTargetWorkerId(), request.getReason(), effectiveAdminId));
    }

    // ─────────────────────────────────────────────────────────────────
    // 3. DISPUTES MANAGEMENT (Rule #2 Double-Entry)
    // ─────────────────────────────────────────────────────────────────

    @GetMapping("/disputes")
    public ResponseEntity<Page<DisputeResponse>> getDisputes(
            @RequestParam(value = "status", required = false) DisputeStatus status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(adminOpsService.getDisputes(status, page, size));
    }

    @PostMapping("/disputes")
    public ResponseEntity<DisputeResponse> createDispute(
            @Valid @RequestBody CreateDisputeRequest request) {
        return ResponseEntity.ok(adminOpsService.createDispute(request));
    }

    @PostMapping("/disputes/{disputeId}/resolve")
    public ResponseEntity<DisputeResponse> resolveDispute(
            @PathVariable("disputeId") UUID disputeId,
            @Valid @RequestBody ResolveDisputeRequest request,
            @RequestHeader(value = "X-Admin-Id", required = false) UUID adminId) {
        UUID effectiveAdminId = adminId != null ? adminId : UUID.fromString("00000000-0000-0000-0000-000000000001");
        return ResponseEntity.ok(adminOpsService.resolveDispute(disputeId, request, effectiveAdminId));
    }

    // ─────────────────────────────────────────────────────────────────
    // 4. LEDGER-BASED WORKER PAYOUTS (Rule #2)
    // ─────────────────────────────────────────────────────────────────

    @GetMapping("/payouts/pending")
    public ResponseEntity<List<PendingPayoutWorker>> getPendingPayouts() {
        return ResponseEntity.ok(adminOpsService.getPendingPayouts());
    }

    @PostMapping("/payouts/batch-settle")
    public ResponseEntity<List<WorkerPayoutResponse>> executeBatchPayouts(
            @Valid @RequestBody BatchPayoutRequest request) {
        return ResponseEntity.ok(adminOpsService.executeBatchPayouts(request));
    }
}

