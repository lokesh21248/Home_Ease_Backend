package com.homeease.backend.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class ExploreServiceDto {

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ExploreServiceRequest {
        @NotBlank(message = "Service name is required")
        private String name;

        private String slug;

        private String description;

        private UUID categoryId;

        @NotBlank(message = "Service image URL is required")
        private String imageUrl;

        @NotNull(message = "Price is required")
        @DecimalMin(value = "0.0", message = "Price cannot be negative")
        private BigDecimal price;

        private BigDecimal originalPrice;

        @DecimalMin(value = "0.0", message = "Rating must be between 0 and 5")
        @DecimalMax(value = "5.0", message = "Rating must be between 0 and 5")
        private BigDecimal rating;

        private Integer reviewCount;

        private Integer displayOrder;

        private Boolean isActive;

        private Boolean isFeatured;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ExploreServiceResponse {
        private UUID id;
        private String name;
        private String slug;
        private String description;
        private UUID categoryId;
        private String categoryName;
        private String imageUrl;
        private BigDecimal price;
        private BigDecimal originalPrice;
        private BigDecimal rating;
        private Integer reviewCount;
        private Integer displayOrder;
        private Boolean isActive;
        private Boolean isFeatured;
        private Instant createdAt;
        private Instant updatedAt;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReorderExploreServicesRequest {
        @NotNull(message = "Items list cannot be null")
        private List<OrderItem> items;

        @Getter
        @Setter
        @NoArgsConstructor
        @AllArgsConstructor
        public static class OrderItem {
            @NotNull(message = "ID is required")
            private UUID id;

            @NotNull(message = "Display order is required")
            private Integer displayOrder;
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToggleActiveRequest {
        @NotNull(message = "isActive is required")
        private Boolean isActive;
    }
}

