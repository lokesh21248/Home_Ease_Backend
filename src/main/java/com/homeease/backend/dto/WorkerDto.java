package com.homeease.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
        private String bookingType; // INSTANT, SCHEDULED
        private String status;
        private String serviceName;
        private String subServiceName;
        private String customerName;
        private String customerPhone;
        private String customerAddress;
        private Double userLat;
        private Double userLng;
        private BigDecimal totalAmount;
        private java.time.Instant scheduledAt;
        private java.time.Instant scheduledEndAt;
        private Integer estimatedArrivalMinutes;
        private java.time.Instant createdAt;
    }

    // --- Worker Availability & Time-Off DTOs ---

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DayScheduleDto {
        private Integer dayOfWeek; // 1 = Monday, 7 = Sunday
        private String dayName;    // "Monday", "Tuesday", etc.
        private Boolean isAvailable;
        private String startTime;      // "08:00"
        private String endTime;        // "20:00"
        private String breakStartTime; // "13:00"
        private String breakEndTime;   // "14:00"
        private Integer slotDurationMins; // 60
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SaveWeeklyAvailabilityRequest {
        @NotNull(message = "Schedule list cannot be null")
        private List<DayScheduleDto> schedule;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TimeOffRequest {
        @NotNull(message = "Start date is required")
        private LocalDate startDate;
        @NotNull(message = "End date is required")
        private LocalDate endDate;
        private String reason;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TimeOffResponse {
        private UUID timeOffId;
        private LocalDate startDate;
        private LocalDate endDate;
        private String reason;
        private Instant createdAt;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class WorkerScheduleSlotDto {
        private UUID bookingId;
        private String serviceName;
        private String customerName;
        private String customerPhone;
        private String address;
        private String timeWindow;
        private String status;
        private Instant scheduledAt;
        private Instant scheduledEndAt;
        private String bookingType;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class WorkerTodayScheduleResponse {
        private String date; // "2026-11-01"
        private Integer totalJobsToday;
        private Integer completedCount;
        private List<WorkerScheduleSlotDto> schedule;
    }
}
