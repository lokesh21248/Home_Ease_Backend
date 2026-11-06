package com.homeease.backend.dto;

import com.homeease.backend.model.enums.PaymentMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class CartDto {

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AddToCartRequest {
        @NotNull(message = "Service ID is required")
        private UUID serviceId;

        private UUID durationId;

        private UUID packageId;

        private UUID subServiceId;

        @Min(value = 1, message = "Quantity must be at least 1")
        @Builder.Default
        private Integer quantity = 1;

        private String notes;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UpdateCartItemRequest {
        @Min(value = 1, message = "Quantity must be at least 1")
        private Integer quantity;

        private String notes;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CartItemResponse {
        private UUID cartItemId;
        private UUID serviceId;
        private String serviceName;
        private UUID durationId;
        private String durationLabel;
        private Integer durationMinutes;
        private UUID packageId;
        private String packageName;
        private UUID subServiceId;
        private String subServiceName;
        private Integer quantity;
        private BigDecimal unitPrice;
        private BigDecimal originalPrice;
        private BigDecimal itemTotal;
        private String imageUrl;
        private String notes;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CartResponse {
        private UUID cartId;
        private UUID userId;
        private List<CartItemResponse> items;
        private Integer totalQuantity;
        private BigDecimal subtotal;
        private BigDecimal additionalCharges;
        private BigDecimal taxAmount;
        private BigDecimal discountAmount;
        private BigDecimal finalPayableAmount;
        private String couponCode;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CartSummaryResponse {
        private Integer itemCount;
        private BigDecimal subtotal;
        private BigDecimal taxAmount;
        private BigDecimal discountAmount;
        private BigDecimal finalPayableAmount;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CheckoutFromCartRequest {
        @NotNull(message = "Booking type is required (INSTANT or SCHEDULED)")
        private String bookingType; // "INSTANT" or "SCHEDULED"

        @NotNull(message = "User latitude is required")
        private Double userLat;

        @NotNull(message = "User longitude is required")
        private Double userLng;

        @NotBlank(message = "Address is required")
        private String address;

        @NotBlank(message = "Pincode is required")
        private String pincode;

        @NotNull(message = "Payment method is required")
        private PaymentMode paymentMethod;

        private LocalDate scheduledDate;

        private String timeSlot;

        private String couponCode;

        private Boolean isForFriend;

        private String recipientName;

        private String recipientPhone;

        private String idempotencyKey;
    }
}

