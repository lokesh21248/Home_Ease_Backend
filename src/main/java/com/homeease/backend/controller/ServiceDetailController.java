package com.homeease.backend.controller;

import com.homeease.backend.dto.ServiceDetailDto.*;
import com.homeease.backend.service.ServiceDetailService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping({"/api/v1/services", "/api/services"})
@CrossOrigin(origins = "*")
public class ServiceDetailController {

    private final ServiceDetailService serviceDetailService;

    public ServiceDetailController(ServiceDetailService serviceDetailService) {
        this.serviceDetailService = serviceDetailService;
    }

    /**
     * Get complete dynamic service details including durations, packages, how-it-works,
     * features, instructions, and real-time availability.
     */
    @GetMapping("/{serviceId}")
    public ResponseEntity<ServiceDetailResponse> getServiceDetail(@PathVariable UUID serviceId) {
        return ResponseEntity.ok(serviceDetailService.getServiceDetail(serviceId));
    }

    /**
     * Get active durations for service
     */
    @GetMapping("/{serviceId}/durations")
    public ResponseEntity<List<DurationDto>> getServiceDurations(@PathVariable UUID serviceId) {
        ServiceDetailResponse detail = serviceDetailService.getServiceDetail(serviceId);
        return ResponseEntity.ok(detail.getDurations());
    }

    /**
     * Get active packages for service
     */
    @GetMapping("/{serviceId}/packages")
    public ResponseEntity<List<PackageDto>> getServicePackages(@PathVariable UUID serviceId) {
        ServiceDetailResponse detail = serviceDetailService.getServiceDetail(serviceId);
        return ResponseEntity.ok(detail.getPackages());
    }

    /**
     * Get features and inclusions for service
     */
    @GetMapping("/{serviceId}/features")
    public ResponseEntity<List<FeatureDto>> getServiceFeatures(@PathVariable UUID serviceId) {
        ServiceDetailResponse detail = serviceDetailService.getServiceDetail(serviceId);
        return ResponseEntity.ok(detail.getFeatures());
    }

    /**
     * Get how it works steps for service
     */
    @GetMapping("/{serviceId}/how-it-works")
    public ResponseEntity<List<HowItWorksDto>> getHowItWorks(@PathVariable UUID serviceId) {
        ServiceDetailResponse detail = serviceDetailService.getServiceDetail(serviceId);
        return ResponseEntity.ok(detail.getHowItWorks());
    }

    /**
     * Get instructions / things to know for service
     */
    @GetMapping("/{serviceId}/instructions")
    public ResponseEntity<List<InstructionDto>> getInstructions(@PathVariable UUID serviceId) {
        ServiceDetailResponse detail = serviceDetailService.getServiceDetail(serviceId);
        return ResponseEntity.ok(detail.getInstructions());
    }
}

