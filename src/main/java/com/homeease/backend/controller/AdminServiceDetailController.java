package com.homeease.backend.controller;

import com.homeease.backend.dto.AdminServiceManagementDto.*;
import com.homeease.backend.dto.ServiceDetailDto.*;
import com.homeease.backend.model.entity.ServiceEntity;
import com.homeease.backend.service.AdminServiceDetailManagementService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@CrossOrigin(origins = "*")
public class AdminServiceDetailController {

    private final AdminServiceDetailManagementService adminService;

    public AdminServiceDetailController(AdminServiceDetailManagementService adminService) {
        this.adminService = adminService;
    }

    // --- Services ---

    @PostMapping("/services/detail")
    public ResponseEntity<ServiceEntity> createService(@Valid @RequestBody CreateServiceRequest req) {
        return ResponseEntity.ok(adminService.createService(req));
    }

    @PutMapping("/services/{id}/detail")
    public ResponseEntity<ServiceEntity> updateService(
            @PathVariable UUID id,
            @Valid @RequestBody CreateServiceRequest req) {
        return ResponseEntity.ok(adminService.updateService(id, req));
    }

    @PutMapping("/services/{id}/status")
    public ResponseEntity<ServiceEntity> toggleStatus(
            @PathVariable UUID id,
            @Valid @RequestBody ToggleServiceStatusRequest req) {
        return ResponseEntity.ok(adminService.toggleStatus(id, req.getIsActive()));
    }

    // --- Durations ---

    @PostMapping("/services/{serviceId}/durations")
    public ResponseEntity<DurationDto> addDuration(
            @PathVariable UUID serviceId,
            @Valid @RequestBody CreateDurationRequest req) {
        return ResponseEntity.ok(adminService.addDuration(serviceId, req));
    }

    @PutMapping("/durations/{durationId}")
    public ResponseEntity<DurationDto> updateDuration(
            @PathVariable UUID durationId,
            @Valid @RequestBody CreateDurationRequest req) {
        return ResponseEntity.ok(adminService.updateDuration(durationId, req));
    }

    @DeleteMapping("/durations/{durationId}")
    public ResponseEntity<Map<String, String>> deleteDuration(@PathVariable UUID durationId) {
        adminService.deleteDuration(durationId);
        return ResponseEntity.ok(Map.of("message", "Duration deleted successfully"));
    }

    // --- Packages ---

    @PostMapping("/services/{serviceId}/packages")
    public ResponseEntity<PackageDto> addPackage(
            @PathVariable UUID serviceId,
            @Valid @RequestBody CreatePackageRequest req) {
        return ResponseEntity.ok(adminService.addPackage(serviceId, req));
    }

    @DeleteMapping("/packages/{packageId}")
    public ResponseEntity<Map<String, String>> deletePackage(@PathVariable UUID packageId) {
        adminService.deletePackage(packageId);
        return ResponseEntity.ok(Map.of("message", "Package deleted successfully"));
    }

    // --- Features ---

    @PostMapping("/services/{serviceId}/features")
    public ResponseEntity<FeatureDto> addFeature(
            @PathVariable UUID serviceId,
            @Valid @RequestBody CreateFeatureRequest req) {
        return ResponseEntity.ok(adminService.addFeature(serviceId, req));
    }

    @DeleteMapping("/features/{featureId}")
    public ResponseEntity<Map<String, String>> deleteFeature(@PathVariable UUID featureId) {
        adminService.deleteFeature(featureId);
        return ResponseEntity.ok(Map.of("message", "Feature deleted successfully"));
    }

    // --- How It Works ---

    @PostMapping("/services/{serviceId}/how-it-works")
    public ResponseEntity<HowItWorksDto> addHowItWorks(
            @PathVariable UUID serviceId,
            @Valid @RequestBody CreateHowItWorksRequest req) {
        return ResponseEntity.ok(adminService.addHowItWorks(serviceId, req));
    }

    @DeleteMapping("/how-it-works/{stepId}")
    public ResponseEntity<Map<String, String>> deleteHowItWorks(@PathVariable UUID stepId) {
        adminService.deleteHowItWorks(stepId);
        return ResponseEntity.ok(Map.of("message", "How-it-works step deleted successfully"));
    }

    // --- Instructions ---

    @PostMapping("/services/{serviceId}/instructions")
    public ResponseEntity<InstructionDto> addInstruction(
            @PathVariable UUID serviceId,
            @Valid @RequestBody CreateInstructionRequest req) {
        return ResponseEntity.ok(adminService.addInstruction(serviceId, req));
    }

    @DeleteMapping("/instructions/{instructionId}")
    public ResponseEntity<Map<String, String>> deleteInstruction(@PathVariable UUID instructionId) {
        adminService.deleteInstruction(instructionId);
        return ResponseEntity.ok(Map.of("message", "Instruction deleted successfully"));
    }

    // --- Images ---

    @PostMapping("/services/{serviceId}/images")
    public ResponseEntity<ImageDto> addImage(
            @PathVariable UUID serviceId,
            @Valid @RequestBody CreateImageRequest req) {
        return ResponseEntity.ok(adminService.addImage(serviceId, req));
    }

    @DeleteMapping("/images/{imageId}")
    public ResponseEntity<Map<String, String>> deleteImage(@PathVariable UUID imageId) {
        adminService.deleteImage(imageId);
        return ResponseEntity.ok(Map.of("message", "Image deleted successfully"));
    }
}

