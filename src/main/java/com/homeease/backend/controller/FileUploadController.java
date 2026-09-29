package com.homeease.backend.controller;

import com.homeease.backend.service.StorageService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/upload")
public class FileUploadController {

    private final StorageService storageService;

    public FileUploadController(StorageService storageService) {
        this.storageService = storageService;
    }

    /**
     * Upload Service Vertical Image (Icon / Illustration)
     * Stored in bucket: homeease-services
     */
    @PostMapping(value = "/service-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> uploadServiceImage(@RequestParam("file") MultipartFile file) {
        String url = storageService.uploadServiceImage(file);
        return ResponseEntity.ok(Map.of("url", url, "bucket", storageService.getServicesBucket()));
    }

    /**
     * Upload Sub-Service Image
     * Stored in bucket: homeease-sub-services
     */
    @PostMapping(value = "/sub-service-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> uploadSubServiceImage(@RequestParam("file") MultipartFile file) {
        String url = storageService.uploadSubServiceImage(file);
        return ResponseEntity.ok(Map.of("url", url, "bucket", storageService.getSubServicesBucket()));
    }

    /**
     * Upload Promotional Banner Image
     * Stored in bucket: homeease-banners
     */
    @PostMapping(value = "/banner-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> uploadBannerImage(@RequestParam("file") MultipartFile file) {
        String url = storageService.uploadBannerImage(file);
        return ResponseEntity.ok(Map.of("url", url, "bucket", storageService.getBannersBucket()));
    }

    /**
     * Upload Worker Profile Avatar
     * Stored in bucket: homeease-worker-profile
     */
    @PostMapping(value = "/worker-profile/{workerId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> uploadWorkerProfilePhoto(
            @PathVariable("workerId") UUID workerId,
            @RequestParam("file") MultipartFile file) {
        String url = storageService.uploadWorkerProfilePhoto(workerId, file);
        return ResponseEntity.ok(Map.of("url", url, "bucket", storageService.getWorkerProfileBucket()));
    }

    /**
     * Upload Worker KYC Document (PAN)
     * Stored in bucket: homeease-worker-kyc
     */
    @PostMapping(value = "/worker-kyc/pan/{workerId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> uploadWorkerPan(
            @PathVariable("workerId") UUID workerId,
            @RequestParam("file") MultipartFile file) {
        String url = storageService.uploadWorkerPanDoc(workerId, file);
        return ResponseEntity.ok(Map.of("url", url, "bucket", storageService.getWorkerKycBucket()));
    }

    /**
     * Upload Worker KYC Document (Aadhaar)
     * Stored in bucket: homeease-worker-kyc
     */
    @PostMapping(value = "/worker-kyc/aadhaar/{workerId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> uploadWorkerAadhaar(
            @PathVariable("workerId") UUID workerId,
            @RequestParam("file") MultipartFile file) {
        String url = storageService.uploadWorkerAadhaarDoc(workerId, file);
        return ResponseEntity.ok(Map.of("url", url, "bucket", storageService.getWorkerKycBucket()));
    }
}
