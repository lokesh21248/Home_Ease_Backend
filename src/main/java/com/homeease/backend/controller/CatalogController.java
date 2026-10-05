package com.homeease.backend.controller;

import com.homeease.backend.dto.CatalogDto.*;
import com.homeease.backend.model.entity.Banner;
import com.homeease.backend.model.entity.Coupon;
import com.homeease.backend.service.CatalogService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping({"/api/v1", "/api/catalog", "/api"})
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping({"/services", "/catalog/services", "/categories", "/catalog/categories"})
    public ResponseEntity<List<ServiceResponse>> getAllActiveServices() {
        return ResponseEntity.ok(catalogService.getAllActiveServices());
    }

    @GetMapping({"/services/{id}", "/catalog/services/{id}", "/categories/{id}", "/catalog/categories/{id}"})
    public ResponseEntity<ServiceResponse> getServiceById(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(catalogService.getServiceById(id));
    }

    @GetMapping({"/services/{id}/sub-services", "/subservices/{id}", "/catalog/subservices/{id}", "/categories/{id}/sub-services"})
    public ResponseEntity<List<SubServiceResponse>> getSubServicesByServiceId(@PathVariable("id") UUID serviceId) {
        return ResponseEntity.ok(catalogService.getSubServicesByServiceId(serviceId));
    }

    @GetMapping({"/banners", "/catalog/banners"})
    public ResponseEntity<List<Banner>> getActiveBanners() {
        return ResponseEntity.ok(catalogService.getActiveBanners());
    }

    @GetMapping({"/coupons", "/catalog/coupons"})
    public ResponseEntity<List<Coupon>> getActiveCoupons() {
        return ResponseEntity.ok(catalogService.getActiveCoupons());
    }

    @PostMapping({"/coupons/validate", "/catalog/coupon/validate"})
    public ResponseEntity<CouponValidateResponse> validateCoupon(@Valid @RequestBody CouponValidateRequest request) {
        return ResponseEntity.ok(catalogService.validateCoupon(request));
    }
}
