package com.homeease.backend.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.homeease.backend.model.enums.DiscountModel;
import com.homeease.backend.model.enums.PricingModel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class CatalogDto {

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ServiceResponse {
        private UUID serviceId;
        private String name;
        private String description;
        private String imageUrl;
        private Boolean isActive;
        private List<SubServiceResponse> subServices;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SubServiceResponse {
        private UUID subServiceId;
        private UUID serviceId;
        private String name;
        private PricingModel pricingType;
        private BigDecimal basePrice;
        private String unitLabel;
        private Integer estimatedMins;
        private String imageUrl;
        private Boolean isActive;
        private List<AddonResponse> addons;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AddonResponse {
        private UUID addonId;
        private UUID subServiceId;
        private String name;
        private BigDecimal price;
        private Boolean isActive;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CouponValidateRequest {
        @NotBlank(message = "Coupon code is required")
        @JsonAlias({"couponCode", "code"})
        private String code;

        @NotNull(message = "Cart order value is required")
        @JsonAlias({"orderAmount", "orderValue", "cartValue", "amount"})
        private BigDecimal orderValue;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CouponValidateResponse {
        private Boolean isValid;
        private String code;
        private DiscountModel discountType;
        private BigDecimal discountVal;
        private BigDecimal calculatedDiscount;
        private String message;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AdminDashboardStats {
        private long totalUsersCount;
        private long totalWorkersCount;
        private long activeWorkersCount;
        private long totalBookingsCount;
        private long completedBookingsCount;
        private BigDecimal totalRevenue;
        private BigDecimal totalPlatformCommission;
    }
}
