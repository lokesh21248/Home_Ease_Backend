package com.homeease.backend.controller;

import com.homeease.backend.dto.ExploreServiceDto.*;
import com.homeease.backend.service.ExploreServiceManager;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/explore-services")
@CrossOrigin(origins = "*")
public class AdminExploreServiceController {

    private final ExploreServiceManager exploreServiceManager;

    public AdminExploreServiceController(ExploreServiceManager exploreServiceManager) {
        this.exploreServiceManager = exploreServiceManager;
    }

    /**
     * Get all explore services for admin management (active & inactive)
     */
    @GetMapping
    public ResponseEntity<List<ExploreServiceResponse>> getAll() {
        return ResponseEntity.ok(exploreServiceManager.getAllForAdmin());
    }

    /**
     * Get single explore service by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<ExploreServiceResponse> getById(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(exploreServiceManager.getById(id));
    }

    /**
     * Create a new explore service
     */
    @PostMapping
    public ResponseEntity<ExploreServiceResponse> create(@Valid @RequestBody ExploreServiceRequest request) {
        ExploreServiceResponse created = exploreServiceManager.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Update an explore service
     */
    @PutMapping("/{id}")
    public ResponseEntity<ExploreServiceResponse> update(
            @PathVariable("id") UUID id,
            @Valid @RequestBody ExploreServiceRequest request) {
        return ResponseEntity.ok(exploreServiceManager.update(id, request));
    }

    /**
     * Toggle active status
     */
    @PatchMapping("/{id}/status")
    public ResponseEntity<ExploreServiceResponse> toggleStatus(
            @PathVariable("id") UUID id,
            @RequestBody ToggleActiveRequest request) {
        return ResponseEntity.ok(exploreServiceManager.toggleActive(id, Boolean.TRUE.equals(request.getIsActive())));
    }

    /**
     * Reorder explore services
     */
    @PatchMapping("/reorder")
    public ResponseEntity<Map<String, Object>> reorder(@Valid @RequestBody ReorderExploreServicesRequest request) {
        exploreServiceManager.reorder(request.getItems());
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Explore services reordered successfully"));
    }

    /**
     * Delete explore service (soft delete by default)
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(
            @PathVariable("id") UUID id,
            @RequestParam(name = "hard", required = false, defaultValue = "false") boolean hard) {
        exploreServiceManager.delete(id, hard);
        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "message", hard ? "Explore service permanently deleted." : "Explore service soft-deleted successfully."
        ));
    }
}

