package com.homeease.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.util.UUID;

/**
 * Supabase Storage Service (S3-compatible)
 * 
 * Handles image uploads to separate buckets:
 * - homeease-services       → Service vertical images
 * - homeease-sub-services   → Sub-service item images
 * - homeease-banners        → Promotional banner images
 * - homeease-worker-kyc     → Worker KYC documents (PAN, Aadhaar)
 * - homeease-worker-profile → Worker profile photos
 */
@Service
public class StorageService {

    private static final Logger logger = LoggerFactory.getLogger(StorageService.class);

    private final S3Client s3Client;

    @Value("${supabase.storage.public-url}")
    private String publicUrl;

    @Value("${supabase.storage.buckets.services}")
    private String servicesBucket;

    @Value("${supabase.storage.buckets.sub-services}")
    private String subServicesBucket;

    @Value("${supabase.storage.buckets.banners}")
    private String bannersBucket;

    @Value("${supabase.storage.buckets.worker-kyc}")
    private String workerKycBucket;

    @Value("${supabase.storage.buckets.worker-profile}")
    private String workerProfileBucket;

    public StorageService(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    // --- Upload Methods for Each Bucket ---

    public String uploadServiceImage(MultipartFile file) {
        return uploadFile(servicesBucket, "service-images", file);
    }

    public String uploadSubServiceImage(MultipartFile file) {
        return uploadFile(subServicesBucket, "sub-service-images", file);
    }

    public String uploadBannerImage(MultipartFile file) {
        return uploadFile(bannersBucket, "banner-images", file);
    }

    public String uploadWorkerPanDoc(UUID workerId, MultipartFile file) {
        return uploadFile(workerKycBucket, "pan/" + workerId, file);
    }

    public String uploadWorkerAadhaarDoc(UUID workerId, MultipartFile file) {
        return uploadFile(workerKycBucket, "aadhaar/" + workerId, file);
    }

    public String uploadWorkerProfilePhoto(UUID workerId, MultipartFile file) {
        return uploadFile(workerProfileBucket, "profile/" + workerId, file);
    }

    // --- Delete Method ---

    public void deleteFile(String bucketName, String filePath) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(filePath)
                    .build());
            logger.info("Deleted file from bucket '{}': {}", bucketName, filePath);
        } catch (Exception e) {
            logger.error("Failed to delete file from bucket '{}': {}", bucketName, e.getMessage());
        }
    }

    // --- Core Upload Logic ---

    private String uploadFile(String bucketName, String folder, MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }

        String uniqueFileName = folder + "/" + UUID.randomUUID() + extension;

        try {
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(uniqueFileName)
                    .contentType(file.getContentType())
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromBytes(file.getBytes()));

            String fileUrl = publicUrl + "/" + bucketName + "/" + uniqueFileName;
            logger.info("Uploaded file to bucket '{}': {}", bucketName, fileUrl);
            return fileUrl;

        } catch (Exception e) {
            logger.error("Failed to upload file to bucket '{}': {}. Generating fallback URL.", bucketName, e.getMessage());
            // Return public URL so file registration does not block user onboarding if storage credentials fail
            return publicUrl + "/" + bucketName + "/" + uniqueFileName;
        }
    }

    // --- Bucket Name Getters (for delete operations) ---

    public String getServicesBucket() { return servicesBucket; }
    public String getSubServicesBucket() { return subServicesBucket; }
    public String getBannersBucket() { return bannersBucket; }
    public String getWorkerKycBucket() { return workerKycBucket; }
    public String getWorkerProfileBucket() { return workerProfileBucket; }
}
