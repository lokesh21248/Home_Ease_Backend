package com.homeease.backend.controller;

import com.homeease.backend.dto.AdminDto.*;
import com.homeease.backend.dto.CatalogDto.AdminDashboardStats;
import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.UserRole;
import com.homeease.backend.service.AdminService;
import com.homeease.backend.service.CatalogService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminService adminService;
    private final CatalogService catalogService;

    public AdminController(AdminService adminService, CatalogService catalogService) {
        this.adminService = adminService;
        this.catalogService = catalogService;
    }

    // --- 1. Dashboard & Analytics APIs ---
    @GetMapping("/dashboard/stats")
    public ResponseEntity<AdminDashboardStats> getDashboardStats() {
        return ResponseEntity.ok(adminService.getDashboardStats());
    }

    // --- 2. Payments Management APIs ---
    @GetMapping("/payments")
    public ResponseEntity<List<PaymentResponse>> getAllPayments() {
        return ResponseEntity.ok(adminService.getAllPayments());
    }

    @GetMapping("/payments/{id}")
    public ResponseEntity<PaymentResponse> getPaymentById(@PathVariable("id") UUID paymentId) {
        return ResponseEntity.ok(adminService.getPaymentById(paymentId));
    }

    @PatchMapping("/payments/{id}/status")
    public ResponseEntity<PaymentResponse> updatePaymentStatus(
            @PathVariable("id") UUID paymentId,
            @Valid @RequestBody PaymentStatusUpdateRequest request) {
        return ResponseEntity.ok(adminService.updatePaymentStatus(paymentId, request.getStatus()));
    }

    // --- 3. Banner Management APIs ---
    @GetMapping("/banners")
    public ResponseEntity<List<Banner>> getAllBannersForAdmin(
            @RequestParam(name = "activeOnly", required = false, defaultValue = "false") boolean activeOnly) {
        return ResponseEntity.ok(activeOnly ? catalogService.getActiveBanners() : catalogService.getAllBannersForAdmin());
    }

    @GetMapping("/banners/{id}")
    public ResponseEntity<Banner> getBannerById(@PathVariable("id") UUID bannerId) {
        return ResponseEntity.ok(catalogService.getBannerById(bannerId));
    }

    @PostMapping("/banners")
    public ResponseEntity<Banner> createBanner(@Valid @RequestBody BannerRequest request) {
        return ResponseEntity.ok(catalogService.createBanner(request));
    }

    @PatchMapping("/banners/{id}")
    public ResponseEntity<Banner> patchBanner(
            @PathVariable("id") UUID bannerId,
            @RequestBody BannerPatchRequest request) {
        return ResponseEntity.ok(catalogService.patchBanner(bannerId, request));
    }

    @DeleteMapping("/banners/{id}")
    public ResponseEntity<String> deleteBanner(
            @PathVariable("id") UUID bannerId,
            @RequestParam(name = "hard", required = false, defaultValue = "false") boolean hard) {
        catalogService.deleteBanner(bannerId, hard);
        return ResponseEntity.ok(hard ? "Banner permanently deleted from database." : "Banner soft-deleted successfully.");
    }

    // --- 4. Coupon Management APIs ---
    @GetMapping("/coupons")
    public ResponseEntity<List<Coupon>> getAllCouponsForAdmin(
            @RequestParam(name = "activeOnly", required = false, defaultValue = "false") boolean activeOnly) {
        return ResponseEntity.ok(activeOnly ? catalogService.getActiveCoupons() : catalogService.getAllCouponsForAdmin());
    }

    @PostMapping("/coupons")
    public ResponseEntity<Coupon> createCoupon(@Valid @RequestBody CouponRequest request) {
        return ResponseEntity.ok(catalogService.createCoupon(request));
    }

    @PatchMapping("/coupons/{id}")
    public ResponseEntity<Coupon> patchCoupon(
            @PathVariable("id") UUID couponId,
            @RequestBody CouponPatchRequest request) {
        return ResponseEntity.ok(catalogService.patchCoupon(couponId, request));
    }

    @DeleteMapping("/coupons/{id}")
    public ResponseEntity<String> deleteCoupon(
            @PathVariable("id") UUID couponId,
            @RequestParam(name = "hard", required = false, defaultValue = "false") boolean hard) {
        catalogService.deleteCoupon(couponId, hard);
        return ResponseEntity.ok(hard ? "Coupon permanently deleted from database." : "Coupon soft-deleted successfully.");
    }

    // --- 5. Live Location Tracking APIs ---
    @GetMapping("/locations/live-map")
    public ResponseEntity<LiveMapOverviewResponse> getLiveMapOverview() {
        return ResponseEntity.ok(adminService.getLiveMapOverview());
    }

    // --- 6. Notification Dispatch & Scheduling APIs ---
    @PostMapping("/notifications/instant")
    public ResponseEntity<String> sendInstantNotification(@Valid @RequestBody InstantNotificationRequest request) {
        return ResponseEntity.ok(adminService.sendInstantNotification(request));
    }

    @PostMapping("/notifications/schedule")
    public ResponseEntity<NotificationResponse> scheduleNotification(@Valid @RequestBody ScheduledNotificationRequest request) {
        return ResponseEntity.ok(adminService.scheduleNotification(request));
    }

    @GetMapping("/notifications")
    public ResponseEntity<List<NotificationResponse>> getAllNotifications() {
        return ResponseEntity.ok(adminService.getAllNotifications());
    }

    // --- 7. Worker Partner Governance APIs ---
    @GetMapping("/workers")
    public ResponseEntity<List<Worker>> getAllWorkers() {
        return ResponseEntity.ok(adminService.getAllWorkers());
    }

    @GetMapping("/workers/{id}")
    public ResponseEntity<Worker> getWorkerById(@PathVariable("id") UUID workerId) {
        return ResponseEntity.ok(adminService.getWorkerById(workerId));
    }

    @PutMapping("/workers/{id}/verify-kyc")
    public ResponseEntity<Worker> verifyWorkerKyc(@PathVariable("id") UUID workerId) {
        return ResponseEntity.ok(adminService.verifyWorkerKyc(workerId));
    }

    @PutMapping("/workers/{id}/block")
    public ResponseEntity<Worker> blockWorker(
            @PathVariable("id") UUID workerId,
            @RequestParam(value = "hours", required = false, defaultValue = "24") Integer hours) {
        return ResponseEntity.ok(adminService.blockWorker(workerId, hours));
    }

    @PutMapping("/workers/{id}/unblock")
    public ResponseEntity<Worker> unblockWorker(@PathVariable("id") UUID workerId) {
        return ResponseEntity.ok(adminService.unblockWorker(workerId));
    }

    // --- 8. User / Customer Management APIs ---
    @GetMapping("/users")
    public ResponseEntity<List<UserResponse>> getAllUsers(
            @RequestParam(value = "role", required = false) UserRole role) {
        return ResponseEntity.ok(adminService.getAllUsers(role));
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<UserResponse> getUserById(@PathVariable("id") UUID userId) {
        return ResponseEntity.ok(adminService.getUserById(userId));
    }

    @PatchMapping("/users/{id}/role")
    public ResponseEntity<UserResponse> updateUserRole(
            @PathVariable("id") UUID userId,
            @Valid @RequestBody UserRoleUpdateRequest request) {
        return ResponseEntity.ok(adminService.updateUserRole(userId, request.getRole()));
    }

    // --- 9. Service Verticals Catalog Management APIs ---
    @GetMapping("/services")
    public ResponseEntity<List<ServiceEntity>> getAllServicesForAdmin() {
        return ResponseEntity.ok(catalogService.getAllServicesForAdmin());
    }

    @PostMapping("/services")
    public ResponseEntity<ServiceEntity> createService(@RequestBody ServiceEntity service) {
        return ResponseEntity.ok(catalogService.createService(service));
    }

    @PutMapping("/services/{id}")
    public ResponseEntity<ServiceEntity> updateService(
            @PathVariable("id") UUID id,
            @RequestBody ServiceEntity service) {
        return ResponseEntity.ok(catalogService.updateService(id, service));
    }

    @DeleteMapping("/services/{id}")
    public ResponseEntity<String> deleteService(@PathVariable("id") UUID id) {
        catalogService.deleteService(id);
        return ResponseEntity.ok("Service vertical soft-deleted successfully.");
    }

    // --- 10. Sub-Services Catalog Management APIs ---
    @GetMapping("/sub-services")
    public ResponseEntity<List<SubService>> getAllSubServicesForAdmin() {
        return ResponseEntity.ok(catalogService.getAllSubServicesForAdmin());
    }

    @PostMapping("/sub-services")
    public ResponseEntity<SubService> createSubService(@RequestBody SubService subService) {
        return ResponseEntity.ok(catalogService.createSubService(subService));
    }

    @PutMapping("/sub-services/{id}")
    public ResponseEntity<SubService> updateSubService(
            @PathVariable("id") UUID id,
            @RequestBody SubService subService) {
        return ResponseEntity.ok(catalogService.updateSubService(id, subService));
    }

    @DeleteMapping("/sub-services/{id}")
    public ResponseEntity<String> deleteSubService(@PathVariable("id") UUID id) {
        catalogService.deleteSubService(id);
        return ResponseEntity.ok("Sub-service item soft-deleted successfully.");
    }
}
