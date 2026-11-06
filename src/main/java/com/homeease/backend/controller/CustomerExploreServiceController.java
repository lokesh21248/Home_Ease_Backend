package com.homeease.backend.controller;

import com.homeease.backend.dto.ExploreServiceDto.ExploreServiceResponse;
import com.homeease.backend.service.ExploreServiceManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping({ "/api/v1/customer/explore-services", "/api/v1/explore-services", "/api/explore-services" })
@CrossOrigin(origins = "*")
public class CustomerExploreServiceController {

    private final ExploreServiceManager exploreServiceManager;

    public CustomerExploreServiceController(ExploreServiceManager exploreServiceManager) {
        this.exploreServiceManager = exploreServiceManager;
    }

    /**
     * Get active explore services for customers, ordered by display order
     */
    @GetMapping
    public ResponseEntity<List<ExploreServiceResponse>> getActiveExploreServices(
            @RequestParam(name = "categoryId", required = false) UUID categoryId) {
        return ResponseEntity.ok(exploreServiceManager.getActiveForCustomer(categoryId));
    }

    /**
     * Get single active explore service details
     */
    @GetMapping("/{id}")
    public ResponseEntity<ExploreServiceResponse> getExploreServiceById(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(exploreServiceManager.getById(id));
    }
}
