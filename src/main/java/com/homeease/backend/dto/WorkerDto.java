package com.homeease.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public class WorkerDto {

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class KycRegisterRequest {
        private String fullName;
        @NotBlank(message = "Address is required")
        private String address;
        @NotBlank(message = "PAN number is required")
        private String panNumber;
        @NotBlank(message = "PAN document URL is required")
        private String panDocUrl;
        private String aadhaarNumber;
        @NotBlank(message = "Aadhaar document URL is required")
        private String aadhaarDocUrl;
        @NotBlank(message = "Bank account number is required")
        private String bankAccountNo;
        @NotBlank(message = "Bank IFSC is required")
        private String bankIfsc;
        private List<UUID> subServiceIds;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class StatusToggleRequest {
        @NotNull(message = "Online status is required")
        private Boolean isOnline;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class LocationUpdateRequest {
        @NotNull(message = "Latitude is required")
        private Double lat;
        @NotNull(message = "Longitude is required")
        private Double lng;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EarningsResponse {
        private BigDecimal totalEarnings;
        private BigDecimal totalCommissionPaid;
        private BigDecimal netPayout;
        private Integer completedBookingsCount;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class WorkerProfileResponse {
        private UUID workerId;
        private UUID userId;
        private Object user;
        private String fullName;
        private String phoneNumber;
        private String email;
        private String address;
        private String panNumber;
        private String panDocUrl;
        private String aadhaarNumber;
        private String aadhaarDocUrl;
        private String bankAccountNo;
        private String bankIfsc;
        private Boolean isOnline;
        private Boolean isVerified;
        private Double currentLat;
        private Double currentLng;
        private Double rating;
        private Integer jobsCompleted;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class WorkerBookingRequestResponse {
        private UUID bookingId;
        private String status;
        private String serviceName;
        private String subServiceName;
        private String customerName;
        private String customerPhone;
        private Double userLat;
        private Double userLng;
        private BigDecimal totalAmount;
        private java.time.Instant scheduledAt;
        private java.time.Instant createdAt;
    }
}
