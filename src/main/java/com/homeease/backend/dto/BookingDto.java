package com.homeease.backend.dto;

import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.PaymentMode;
import com.homeease.backend.model.enums.TransactionState;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class BookingDto {

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SubServiceRequestItem {
        @NotNull(message = "SubService ID is required")
        private UUID subServiceId;
        @NotNull(message = "Quantity is required")
        private Integer quantity;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AddonRequestItem {
        @NotNull(message = "Addon ID is required")
        private UUID addonId;
        @NotNull(message = "Quantity is required")
        private Integer quantity;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateBookingRequest {
        @NotNull(message = "Service ID is required")
        private UUID serviceId;
        @NotNull(message = "Sub-services list cannot be empty")
        private List<SubServiceRequestItem> subServices;
        private List<AddonRequestItem> addons;
        private String couponCode;
        @NotNull(message = "Scheduled time is required")
        private Instant scheduledAt;
        @NotNull(message = "User latitude is required")
        private Double userLat;
        @NotNull(message = "User longitude is required")
        private Double userLng;
        @NotNull(message = "Payment method is required")
        private PaymentMode paymentMethod;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PinVerifyRequest {
        @NotBlank(message = "4-digit PIN is required")
        private String pinCode;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AddExtraSubServiceRequest {
        @NotNull(message = "SubService ID is required")
        private UUID subServiceId;
        @NotNull(message = "Quantity is required")
        private Integer quantity;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class BookingResponse {
        private UUID bookingId;
        private UUID userId;
        private String userName;
        private String userPhone;
        private UUID workerId;
        private String workerName;
        private String workerPhone;
        private UUID serviceId;
        private String serviceName;
        private BookingStage status;
        private String pinCode; // Included for customer
        private Instant scheduledAt;
        private Instant startedAt;
        private Instant completedAt;
        private Double userLat;
        private Double userLng;
        private BigDecimal baseAmount;
        private BigDecimal extraAmount;
        private BigDecimal discountAmount;
        private BigDecimal totalAmount;
        private TransactionState paymentStatus;
        private PaymentMode paymentMethod;
        private Instant createdAt;
    }
}
