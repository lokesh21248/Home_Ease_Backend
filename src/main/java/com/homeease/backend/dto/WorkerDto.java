package com.homeease.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class WorkerDto {

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class KycRegisterRequest {
        private UUID userId;
        private String fullName;
        private String address;
        private String panNumber;
        private String panDocUrl;
        private String aadhaarNumber;
        private String aadhaarDocUrl;
        private String bankAccountNo;
        private String bankIfsc;
        private List<String> subServiceIds;
        private String avatarUrl;
        private String gender;
        private LocalDate dob;
        private Integer experienceYears;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class KycSubmissionResponse {
        private UUID workerId;
        private Boolean isVerified;
        private String kycStatus;
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
        private Double bearing;   // Heading in degrees (0-360) for marker rotation
        private Double speed;     // Speed in km/h
        private Double accuracy;  // GPS accuracy in metres
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
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class WorkerProfileResponse {
        private UUID workerId;
        private UUID userId;
        private Object user;
        private String fullName;
        private String phoneNumber;
        private String email;
        private String address;
        private String avatarUrl;
        private String panNumber;
        private String panDocUrl;
        private String aadhaarNumber;
        private String aadhaarDocUrl;
        private String bankAccountNo;
        private String bankIfsc;
        private Boolean isOnline;
        private Boolean isVerified;
        private String kycStatus;
        private String gender;
        private LocalDate dob;
        private Integer experienceYears;
        private List<String> subServiceIds;
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
