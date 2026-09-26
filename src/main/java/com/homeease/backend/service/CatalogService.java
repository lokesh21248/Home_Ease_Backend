package com.homeease.backend.service;

import com.homeease.backend.dto.AdminDto.*;
import com.homeease.backend.dto.CatalogDto.*;
import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.DiscountModel;
import com.homeease.backend.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CatalogService {

    private final ServiceRepository serviceRepository;
    private final SubServiceRepository subServiceRepository;
    private final ServiceAddonRepository addonRepository;
    private final CouponRepository couponRepository;
    private final BannerRepository bannerRepository;

    public CatalogService(ServiceRepository serviceRepository,
                          SubServiceRepository subServiceRepository,
                          ServiceAddonRepository addonRepository,
                          CouponRepository couponRepository,
                          BannerRepository bannerRepository) {
        this.serviceRepository = serviceRepository;
        this.subServiceRepository = subServiceRepository;
        this.addonRepository = addonRepository;
        this.couponRepository = couponRepository;
        this.bannerRepository = bannerRepository;
    }

    @Transactional(readOnly = true)
    public List<ServiceResponse> getAllActiveServices() {
        return serviceRepository.findByIsActiveTrue().stream()
                .map(this::mapServiceToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ServiceResponse getServiceById(UUID serviceId) {
        ServiceEntity service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new RuntimeException("Service vertical not found"));
        return mapServiceToResponse(service);
    }

    @Transactional(readOnly = true)
    public List<SubServiceResponse> getSubServicesByServiceId(UUID serviceId) {
        return subServiceRepository.findByService_ServiceIdAndIsActiveTrue(serviceId).stream()
                .map(this::mapSubServiceToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<Banner> getActiveBanners() {
        return bannerRepository.findByIsActiveTrue();
    }

    @Transactional(readOnly = true)
    public CouponValidateResponse validateCoupon(CouponValidateRequest request) {
        Coupon coupon = couponRepository.findByCodeAndIsActiveTrue(request.getCode())
                .orElse(null);

        if (coupon == null) {
            return CouponValidateResponse.builder()
                    .isValid(false)
                    .code(request.getCode())
                    .message("Coupon code is invalid or expired")
                    .calculatedDiscount(BigDecimal.ZERO)
                    .build();
        }

        Instant now = Instant.now();
        if (now.isBefore(coupon.getValidFrom()) || now.isAfter(coupon.getValidUntil())) {
            return CouponValidateResponse.builder()
                    .isValid(false)
                    .code(request.getCode())
                    .message("Coupon code is out of valid date range")
                    .calculatedDiscount(BigDecimal.ZERO)
                    .build();
        }

        if (coupon.getUsageLimit() != null && coupon.getTimesUsed() >= coupon.getUsageLimit()) {
            return CouponValidateResponse.builder()
                    .isValid(false)
                    .code(request.getCode())
                    .message("Coupon usage limit exceeded")
                    .calculatedDiscount(BigDecimal.ZERO)
                    .build();
        }

        if (request.getOrderValue().compareTo(coupon.getMinOrderValue()) < 0) {
            return CouponValidateResponse.builder()
                    .isValid(false)
                    .code(request.getCode())
                    .message("Minimum order value of ₹" + coupon.getMinOrderValue() + " required")
                    .calculatedDiscount(BigDecimal.ZERO)
                    .build();
        }

        BigDecimal discount = BigDecimal.ZERO;
        if (coupon.getDiscountType() == DiscountModel.PERCENTAGE) {
            discount = request.getOrderValue()
                    .multiply(coupon.getDiscountVal())
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            if (coupon.getMaxDiscountAmount() != null && discount.compareTo(coupon.getMaxDiscountAmount()) > 0) {
                discount = coupon.getMaxDiscountAmount();
            }
        } else {
            discount = coupon.getDiscountVal();
        }

        return CouponValidateResponse.builder()
                .isValid(true)
                .code(coupon.getCode())
                .discountType(coupon.getDiscountType())
                .discountVal(coupon.getDiscountVal())
                .calculatedDiscount(discount)
                .message("Coupon applied successfully!")
                .build();
    }

    @Transactional
    public ServiceEntity createService(ServiceEntity service) {
        return serviceRepository.save(service);
    }

    @Transactional
    public ServiceEntity updateService(UUID serviceId, ServiceEntity details) {
        ServiceEntity service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new RuntimeException("Service vertical not found"));
        service.setName(details.getName());
        service.setDescription(details.getDescription());
        service.setImageUrl(details.getImageUrl());
        if (details.getIsActive() != null) {
            service.setIsActive(details.getIsActive());
        }
        return serviceRepository.save(service);
    }

    @Transactional
    public void deleteService(UUID serviceId) {
        ServiceEntity service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new RuntimeException("Service vertical not found"));
        service.setIsActive(false); // Soft delete
        serviceRepository.save(service);
    }

    @Transactional
    public SubService createSubService(SubService subService) {
        return subServiceRepository.save(subService);
    }

    @Transactional
    public SubService updateSubService(UUID subServiceId, SubService details) {
        SubService subService = subServiceRepository.findById(subServiceId)
                .orElseThrow(() -> new RuntimeException("Sub-service not found"));
        subService.setName(details.getName());
        subService.setBasePrice(details.getBasePrice());
        subService.setPricingType(details.getPricingType());
        subService.setUnitLabel(details.getUnitLabel());
        subService.setEstimatedMins(details.getEstimatedMins());
        subService.setImageUrl(details.getImageUrl());
        if (details.getIsActive() != null) {
            subService.setIsActive(details.getIsActive());
        }
        return subServiceRepository.save(subService);
    }

    @Transactional
    public void deleteSubService(UUID subServiceId) {
        SubService subService = subServiceRepository.findById(subServiceId)
                .orElseThrow(() -> new RuntimeException("Sub-service not found"));
        subService.setIsActive(false); // Soft delete
        subServiceRepository.save(subService);
    }

    @Transactional(readOnly = true)
    public List<ServiceEntity> getAllServicesForAdmin() {
        return serviceRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<SubService> getAllSubServicesForAdmin() {
        return subServiceRepository.findAll();
    }

    // --- Admin Banner Management APIs ---
    @Transactional(readOnly = true)
    public List<Banner> getAllBannersForAdmin() {
        return bannerRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Banner getBannerById(UUID bannerId) {
        return bannerRepository.findById(bannerId)
                .orElseThrow(() -> new RuntimeException("Banner not found with ID: " + bannerId));
    }

    @Transactional
    public Banner createBanner(BannerRequest request) {
        Banner banner = Banner.builder()
                .title(request.getTitle())
                .imageUrl(request.getImageUrl())
                .targetType(request.getTargetType())
                .targetId(request.getTargetId())
                .isActive(request.getIsActive() != null ? request.getIsActive() : true)
                .build();
        return bannerRepository.save(banner);
    }

    @Transactional
    public Banner patchBanner(UUID bannerId, BannerPatchRequest request) {
        Banner banner = bannerRepository.findById(bannerId)
                .orElseThrow(() -> new RuntimeException("Banner not found"));
        if (request.getTitle() != null) banner.setTitle(request.getTitle());
        if (request.getImageUrl() != null) banner.setImageUrl(request.getImageUrl());
        if (request.getTargetType() != null) banner.setTargetType(request.getTargetType());
        if (request.getTargetId() != null) banner.setTargetId(request.getTargetId());
        if (request.getIsActive() != null) banner.setIsActive(request.getIsActive());
        return bannerRepository.save(banner);
    }

    @Transactional
    public void deleteBanner(UUID bannerId) {
        deleteBanner(bannerId, false);
    }

    @Transactional
    public void deleteBanner(UUID bannerId, boolean hard) {
        Banner banner = bannerRepository.findById(bannerId)
                .orElseThrow(() -> new RuntimeException("Banner not found"));
        if (hard) {
            bannerRepository.delete(banner);
        } else {
            banner.setIsActive(false); // Soft delete
            bannerRepository.save(banner);
        }
    }

    // --- Admin Coupon Management APIs ---
    @Transactional(readOnly = true)
    public List<Coupon> getAllCouponsForAdmin() {
        return couponRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Coupon> getActiveCoupons() {
        return couponRepository.findByIsActiveTrue();
    }

    @Transactional
    public Coupon createCoupon(CouponRequest request) {
        String upperCode = request.getCode().toUpperCase();
        Optional<Coupon> existingOpt = couponRepository.findByCode(upperCode);
        if (existingOpt.isPresent()) {
            Coupon existing = existingOpt.get();
            if (Boolean.TRUE.equals(existing.getIsActive())) {
                throw new RuntimeException("Coupon with code '" + upperCode + "' already exists and is active.");
            }
            // Reactivate previously soft-deleted coupon with new configuration
            existing.setDiscountType(request.getDiscountType());
            existing.setDiscountVal(request.getDiscountVal());
            existing.setMinOrderValue(request.getMinOrderValue() != null ? request.getMinOrderValue() : BigDecimal.ZERO);
            existing.setMaxDiscountAmount(request.getMaxDiscountAmount());
            existing.setValidFrom(request.getValidFrom());
            existing.setValidUntil(request.getValidUntil());
            existing.setUsageLimit(request.getUsageLimit());
            existing.setIsActive(request.getIsActive() != null ? request.getIsActive() : true);
            existing.setTimesUsed(0);
            return couponRepository.save(existing);
        }

        Coupon coupon = Coupon.builder()
                .code(upperCode)
                .discountType(request.getDiscountType())
                .discountVal(request.getDiscountVal())
                .minOrderValue(request.getMinOrderValue() != null ? request.getMinOrderValue() : BigDecimal.ZERO)
                .maxDiscountAmount(request.getMaxDiscountAmount())
                .validFrom(request.getValidFrom())
                .validUntil(request.getValidUntil())
                .usageLimit(request.getUsageLimit())
                .isActive(request.getIsActive() != null ? request.getIsActive() : true)
                .build();
        return couponRepository.save(coupon);
    }

    @Transactional
    public Coupon patchCoupon(UUID couponId, CouponPatchRequest request) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new RuntimeException("Coupon not found"));
        if (request.getCode() != null) coupon.setCode(request.getCode().toUpperCase());
        if (request.getDiscountType() != null) coupon.setDiscountType(request.getDiscountType());
        if (request.getDiscountVal() != null) coupon.setDiscountVal(request.getDiscountVal());
        if (request.getMinOrderValue() != null) coupon.setMinOrderValue(request.getMinOrderValue());
        if (request.getMaxDiscountAmount() != null) coupon.setMaxDiscountAmount(request.getMaxDiscountAmount());
        if (request.getValidFrom() != null) coupon.setValidFrom(request.getValidFrom());
        if (request.getValidUntil() != null) coupon.setValidUntil(request.getValidUntil());
        if (request.getUsageLimit() != null) coupon.setUsageLimit(request.getUsageLimit());
        if (request.getIsActive() != null) coupon.setIsActive(request.getIsActive());
        return couponRepository.save(coupon);
    }

    @Transactional
    public void deleteCoupon(UUID couponId) {
        deleteCoupon(couponId, false);
    }

    @Transactional
    public void deleteCoupon(UUID couponId, boolean hard) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new RuntimeException("Coupon not found"));
        if (hard) {
            couponRepository.delete(coupon);
        } else {
            coupon.setIsActive(false); // Soft delete
            couponRepository.save(coupon);
        }
    }

    private ServiceResponse mapServiceToResponse(ServiceEntity service) {
        List<SubServiceResponse> subServices = subServiceRepository.findByService_ServiceIdAndIsActiveTrue(service.getServiceId())
                .stream().map(this::mapSubServiceToResponse).collect(Collectors.toList());

        return ServiceResponse.builder()
                .serviceId(service.getServiceId())
                .name(service.getName())
                .description(service.getDescription())
                .imageUrl(service.getImageUrl())
                .isActive(service.getIsActive())
                .subServices(subServices)
                .build();
    }

    private SubServiceResponse mapSubServiceToResponse(SubService subService) {
        List<AddonResponse> addons = addonRepository.findBySubService_SubServiceIdAndIsActiveTrue(subService.getSubServiceId())
                .stream().map(a -> AddonResponse.builder()
                        .addonId(a.getAddonId())
                        .subServiceId(subService.getSubServiceId())
                        .name(a.getName())
                        .price(a.getPrice())
                        .isActive(a.getIsActive())
                        .build()).collect(Collectors.toList());

        return SubServiceResponse.builder()
                .subServiceId(subService.getSubServiceId())
                .serviceId(subService.getService().getServiceId())
                .name(subService.getName())
                .pricingType(subService.getPricingType())
                .basePrice(subService.getBasePrice())
                .unitLabel(subService.getUnitLabel())
                .estimatedMins(subService.getEstimatedMins())
                .imageUrl(subService.getImageUrl())
                .isActive(subService.getIsActive())
                .addons(addons)
                .build();
    }
}
