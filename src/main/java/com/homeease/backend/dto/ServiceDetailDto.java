package com.homeease.backend.dto;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public class ServiceDetailDto {

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ServiceDetailResponse {
        private UUID serviceId;
        private String serviceName;
        private String subtitle;
        private String description;
        private String bannerImage;
        private BigDecimal rating;
        private Integer totalReviews;
        private BigDecimal startingPrice;
        private List<String> bookingTypes;
        private String cancellationPolicy;
        private ServiceAvailabilityDto serviceAvailability;
        private List<DurationDto> durations;
        private List<PackageDto> packages;
        private List<FeatureDto> features;
        private List<HowItWorksDto> howItWorks;
        private List<InstructionDto> instructions;
        private List<ImageDto> galleryImages;
        private List<SubServiceResponseItem> subServices;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ServiceAvailabilityDto {
        private Boolean isAvailableNow;
        private String nextAvailableSlot;
        private Integer instantEtaMinutes;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DurationDto {
        private UUID durationId;
        private Integer minutes;
        private String label;
        private BigDecimal price;
        private BigDecimal originalPrice;
        private Boolean isPopular;
        private Boolean active;
        private Integer displayOrder;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PackageDto {
        private UUID packageId;
        private String name;
        private String description;
        private BigDecimal price;
        private BigDecimal originalPrice;
        private Integer durationMins;
        private List<String> inclusions;
        private Boolean active;
        private Integer displayOrder;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FeatureDto {
        private UUID featureId;
        private String title;
        private String description;
        private String iconUrl;
        private Boolean isIncluded;
        private Integer displayOrder;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class HowItWorksDto {
        private UUID stepId;
        private Integer stepNumber;
        private String title;
        private String description;
        private String iconUrl;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class InstructionDto {
        private UUID instructionId;
        private String title;
        private String description;
        private Integer displayOrder;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ImageDto {
        private UUID imageId;
        private String imageUrl;
        private String caption;
        private Boolean isBanner;
        private Integer displayOrder;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SubServiceResponseItem {
        private UUID subServiceId;
        private String name;
        private String pricingType;
        private BigDecimal basePrice;
        private String unitLabel;
        private Integer estimatedMins;
        private String imageUrl;
        private Boolean isActive;
    }
}

