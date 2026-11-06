package com.homeease.backend.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public class AdminServiceManagementDto {

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateServiceRequest {
        @NotBlank(message = "Service name is required")
        private String name;

        private String subtitle;
        private String description;

        @NotBlank(message = "Image URL is required")
        private String imageUrl;

        private String bannerImage;

        @DecimalMin(value = "0.0", message = "Rating must be >= 0")
        private BigDecimal rating;

        private Integer totalReviews;

        @DecimalMin(value = "0.0", message = "Starting price must be >= 0")
        private BigDecimal startingPrice;

        private String cancellationPolicy;
        private String bookingTypes;
        private Boolean isActive;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateDurationRequest {
        @NotNull(message = "Minutes is required")
        @Min(value = 1, message = "Minutes must be > 0")
        private Integer minutes;

        private String label;

        @NotNull(message = "Price is required")
        @DecimalMin(value = "0.0", message = "Price must be >= 0")
        private BigDecimal price;

        private BigDecimal originalPrice;
        private Boolean isPopular;
        private Boolean isActive;
        private Integer displayOrder;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreatePackageRequest {
        @NotBlank(message = "Package name is required")
        private String name;

        private String description;

        @NotNull(message = "Price is required")
        @DecimalMin(value = "0.0", message = "Price must be >= 0")
        private BigDecimal price;

        private BigDecimal originalPrice;
        private Integer durationMins;
        private List<String> inclusions;
        private Boolean isActive;
        private Integer displayOrder;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateFeatureRequest {
        @NotBlank(message = "Title is required")
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
    public static class CreateHowItWorksRequest {
        @NotNull(message = "Step number is required")
        private Integer stepNumber;

        @NotBlank(message = "Title is required")
        private String title;

        private String description;
        private String iconUrl;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateInstructionRequest {
        @NotBlank(message = "Title is required")
        private String title;

        private String description;
        private Integer displayOrder;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateImageRequest {
        @NotBlank(message = "Image URL is required")
        private String imageUrl;

        private String caption;
        private Boolean isBanner;
        private Integer displayOrder;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToggleServiceStatusRequest {
        @NotNull(message = "isActive is required")
        private Boolean isActive;
    }
}

