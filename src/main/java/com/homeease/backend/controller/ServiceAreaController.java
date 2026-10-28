package com.homeease.backend.controller;

import com.homeease.backend.dto.ServiceAreaDto.*;
import com.homeease.backend.service.ServiceAreaService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Two responsibility areas:
 *
 *  1. Admin APIs  — /api/v1/admin/service-areas/**
 *     Require X-User-Id header from a UserRole.ADMIN caller.
 *     ServiceAreaService.requireAdmin() enforces this.
 *
 *  2. Customer API — /api/v1/serviceability/check
 *     Public — no authentication required.
 */
@RestController
@CrossOrigin(origins = "*")
public class ServiceAreaController {

    private final ServiceAreaService serviceAreaService;

    public ServiceAreaController(ServiceAreaService serviceAreaService) {
        this.serviceAreaService = serviceAreaService;
    }

    // ─────────────────────────────────────────────────────────────────
    // OVERVIEW
    // ─────────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/admin/service-areas")
    public ResponseEntity<ServiceAreaOverviewResponse> getOverview() {
        return ResponseEntity.ok(serviceAreaService.getOverview());
    }

    // ─────────────────────────────────────────────────────────────────
    // STATES
    // ─────────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/admin/service-areas/states")
    public ResponseEntity<List<StateResponse>> getAllStates() {
        return ResponseEntity.ok(serviceAreaService.getAllStates());
    }

    @PostMapping("/api/v1/admin/service-areas/states")
    public ResponseEntity<StateResponse> createState(
            @RequestHeader("X-User-Id") UUID callerId,
            @Valid @RequestBody CreateStateRequest request) {
        return ResponseEntity.ok(serviceAreaService.createState(callerId, request));
    }

    @PutMapping("/api/v1/admin/service-areas/states/{id}")
    public ResponseEntity<Object> updateState(
            @RequestHeader("X-User-Id") UUID callerId,
            @PathVariable("id") UUID stateId,
            @RequestBody UpdateStateRequest request,
            @RequestParam(value = "confirm", defaultValue = "false") boolean confirm) {
        return ResponseEntity.ok(serviceAreaService.updateState(callerId, stateId, request, confirm));
    }

    // ─────────────────────────────────────────────────────────────────
    // CITIES
    // ─────────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/admin/service-areas/cities")
    public ResponseEntity<List<CityResponse>> getCities(
            @RequestParam(value = "stateId", required = false) UUID stateId) {
        return ResponseEntity.ok(serviceAreaService.getCities(stateId));
    }

    @PostMapping("/api/v1/admin/service-areas/cities")
    public ResponseEntity<CityResponse> createCity(
            @RequestHeader("X-User-Id") UUID callerId,
            @Valid @RequestBody CreateCityRequest request) {
        return ResponseEntity.ok(serviceAreaService.createCity(callerId, request));
    }

    @PutMapping("/api/v1/admin/service-areas/cities/{id}")
    public ResponseEntity<Object> updateCity(
            @RequestHeader("X-User-Id") UUID callerId,
            @PathVariable("id") UUID cityId,
            @RequestBody UpdateCityRequest request,
            @RequestParam(value = "confirm", defaultValue = "false") boolean confirm) {
        return ResponseEntity.ok(serviceAreaService.updateCity(callerId, cityId, request, confirm));
    }

    // ─────────────────────────────────────────────────────────────────
    // PINCODES
    // ─────────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/admin/service-areas/pincodes")
    public ResponseEntity<PincodePage> searchPincodes(
            @RequestParam(value = "stateId", required = false) UUID stateId,
            @RequestParam(value = "cityId",  required = false) UUID cityId,
            @RequestParam(value = "active",  required = false) Boolean active,
            @RequestParam(value = "q",       required = false) String q,
            @RequestParam(value = "page",    defaultValue = "0") int page,
            @RequestParam(value = "size",    defaultValue = "20") int size) {
        return ResponseEntity.ok(serviceAreaService.searchPincodes(stateId, cityId, active, q, page, size));
    }

    @PostMapping("/api/v1/admin/service-areas/pincodes")
    public ResponseEntity<PincodeResponse> createPincode(
            @RequestHeader("X-User-Id") UUID callerId,
            @Valid @RequestBody CreatePincodeRequest request) {
        return ResponseEntity.ok(serviceAreaService.createPincode(callerId, request));
    }

    @PostMapping("/api/v1/admin/service-areas/pincodes/bulk")
    public ResponseEntity<List<PincodeResponse>> bulkCreatePincodes(
            @RequestHeader("X-User-Id") UUID callerId,
            @Valid @RequestBody List<CreatePincodeRequest> requests) {
        return ResponseEntity.ok(serviceAreaService.bulkCreatePincodes(callerId, requests));
    }

    @PutMapping("/api/v1/admin/service-areas/pincodes/{id}")
    public ResponseEntity<Object> updatePincode(
            @RequestHeader("X-User-Id") UUID callerId,
            @PathVariable("id") UUID pincodeId,
            @RequestBody UpdatePincodeRequest request,
            @RequestParam(value = "confirm", defaultValue = "false") boolean confirm) {
        return ResponseEntity.ok(serviceAreaService.updatePincode(callerId, pincodeId, request, confirm));
    }

    // ─────────────────────────────────────────────────────────────────
    // CUSTOMER PUBLIC ENDPOINT
    // ─────────────────────────────────────────────────────────────────

    /**
     * GET /api/v1/serviceability/check?pincode=500072
     *
     * Publicly accessible — no authentication required.
     * Returns a safe response: serviceable flag + city/state name + message.
     * Never exposes internal IDs or admin metadata.
     */
    @GetMapping({"/api/v1/serviceability/check", "/api/serviceability/check"})
    public ResponseEntity<ServiceabilityResponse> checkServiceability(
            @RequestParam("pincode") String pincode) {
        return ResponseEntity.ok(serviceAreaService.checkServiceability(pincode));
    }
}
