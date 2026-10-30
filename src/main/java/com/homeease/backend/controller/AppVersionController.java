package com.homeease.backend.controller;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * App Version & Remote Update Check Controller.
 * Provides version verification, force update policies, and store URLs
 * for Customer App, Admin Dashboard, and Partner App.
 */
@RestController
@CrossOrigin(origins = "*")
public class AppVersionController {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AppVersionConfig {
        private String appType;          // CUSTOMER, ADMIN, PARTNER
        private String latestVersion;     // e.g. "1.2.0"
        private String minimumVersion;    // e.g. "1.0.0"
        private boolean forceUpdate;      // true if all versions < latestVersion must upgrade
        private String releaseNotes;
        private String storeUrl;          // Play Store / App Store or Web URL
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CheckUpdateResponse {
        private boolean updateAvailable;
        private boolean forceUpdate;
        private String currentVersion;
        private String latestVersion;
        private String minimumVersion;
        private String releaseNotes;
        private String storeUrl;
        private String appType;
    }

    private final Map<String, AppVersionConfig> versionRegistry = new ConcurrentHashMap<>();

    public AppVersionController() {
        // Initialize default production configurations
        versionRegistry.put("CUSTOMER", AppVersionConfig.builder()
                .appType("CUSTOMER")
                .latestVersion("1.2.0")
                .minimumVersion("1.0.0")
                .forceUpdate(false)
                .releaseNotes("Reliable Google Maps geocoding, doorstep serviceability checks, and friend booking support.")
                .storeUrl("https://play.google.com/store/apps/details?id=com.homeease.customer")
                .build());

        versionRegistry.put("ADMIN", AppVersionConfig.builder()
                .appType("ADMIN")
                .latestVersion("1.1.0")
                .minimumVersion("1.0.0")
                .forceUpdate(false)
                .releaseNotes("Kafka booking analytics monitoring, service area hierarchy management, and worker KYC governance.")
                .storeUrl("https://admin.firstdoor.in")
                .build());

        versionRegistry.put("PARTNER", AppVersionConfig.builder()
                .appType("PARTNER")
                .latestVersion("1.1.0")
                .minimumVersion("1.0.0")
                .forceUpdate(false)
                .releaseNotes("Live GPS tracking, real-time job dispatch, and instant wallet payouts.")
                .storeUrl("https://play.google.com/store/apps/details?id=com.homeease.partner")
                .build());
    }

    /**
     * Public endpoint: Check for app updates
     * GET /api/v1/app/check-update?appType=CUSTOMER&currentVersion=1.0.0&platform=android
     */
    @GetMapping("/api/v1/app/check-update")
    public ResponseEntity<CheckUpdateResponse> checkUpdate(
            @RequestParam(name = "appType", defaultValue = "CUSTOMER") String appType,
            @RequestParam(name = "currentVersion", defaultValue = "1.0.0") String currentVersion,
            @RequestParam(name = "platform", required = false, defaultValue = "android") String platform) {

        String key = appType.toUpperCase().trim();
        AppVersionConfig config = versionRegistry.getOrDefault(key, versionRegistry.get("CUSTOMER"));

        boolean updateAvailable = compareVersions(currentVersion, config.getLatestVersion()) < 0;
        boolean forceUpdate = config.isForceUpdate() || compareVersions(currentVersion, config.getMinimumVersion()) < 0;

        CheckUpdateResponse response = CheckUpdateResponse.builder()
                .updateAvailable(updateAvailable)
                .forceUpdate(updateAvailable && forceUpdate)
                .currentVersion(currentVersion)
                .latestVersion(config.getLatestVersion())
                .minimumVersion(config.getMinimumVersion())
                .releaseNotes(config.getReleaseNotes())
                .storeUrl(config.getStoreUrl())
                .appType(config.getAppType())
                .build();

        return ResponseEntity.ok(response);
    }

    /**
     * Admin endpoint: Get all app version configurations
     * GET /api/v1/admin/app-versions
     */
    @GetMapping("/api/v1/admin/app-versions")
    public ResponseEntity<Map<String, AppVersionConfig>> getAllAppVersions() {
        return ResponseEntity.ok(Collections.unmodifiableMap(new LinkedHashMap<>(versionRegistry)));
    }

    /**
     * Admin endpoint: Update version config for an app
     * PUT /api/v1/admin/app-versions/{appType}
     */
    @PutMapping("/api/v1/admin/app-versions/{appType}")
    public ResponseEntity<AppVersionConfig> updateAppVersionConfig(
            @PathVariable("appType") String appType,
            @RequestBody AppVersionConfig updateRequest) {

        String key = appType.toUpperCase().trim();
        AppVersionConfig current = versionRegistry.getOrDefault(key, AppVersionConfig.builder().appType(key).build());

        AppVersionConfig updated = AppVersionConfig.builder()
                .appType(key)
                .latestVersion(updateRequest.getLatestVersion() != null ? updateRequest.getLatestVersion() : current.getLatestVersion())
                .minimumVersion(updateRequest.getMinimumVersion() != null ? updateRequest.getMinimumVersion() : current.getMinimumVersion())
                .forceUpdate(updateRequest.isForceUpdate())
                .releaseNotes(updateRequest.getReleaseNotes() != null ? updateRequest.getReleaseNotes() : current.getReleaseNotes())
                .storeUrl(updateRequest.getStoreUrl() != null ? updateRequest.getStoreUrl() : current.getStoreUrl())
                .build();

        versionRegistry.put(key, updated);
        return ResponseEntity.ok(updated);
    }

    /**
     * Helper to compare semantic versions:
     * returns < 0 if v1 < v2, 0 if v1 == v2, > 0 if v1 > v2
     */
    private int compareVersions(String v1, String v2) {
        if (v1 == null) v1 = "0.0.0";
        if (v2 == null) v2 = "0.0.0";

        String[] parts1 = v1.replaceAll("[^0-9.]", "").split("\\.");
        String[] parts2 = v2.replaceAll("[^0-9.]", "").split("\\.");

        int length = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < length; i++) {
            int num1 = i < parts1.length && !parts1[i].isBlank() ? Integer.parseInt(parts1[i]) : 0;
            int num2 = i < parts2.length && !parts2[i].isBlank() ? Integer.parseInt(parts2[i]) : 0;
            if (num1 != num2) {
                return Integer.compare(num1, num2);
            }
        }
        return 0;
    }
}
