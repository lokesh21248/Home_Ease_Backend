package com.homeease.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.BookingType;
import com.homeease.backend.model.enums.OverrideType;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public class AdminCalendarDto {

    // --- 1. Calendar Event for Day / Week / Month View ---
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CalendarEventDto {
        private UUID bookingId;
        private String jobId;
        private UUID customerId;
        private String customerName;
        private String customerPhone;
        private UUID workerId;
        private String workerName;
        private String workerPhone;
        private UUID serviceId;
        private String serviceName;
        private BookingType bookingType; // INSTANT, SCHEDULED
        private BookingStage status;
        private Instant startAt;
        private Instant endAt;
        private String timeWindow;
        private BigDecimal totalAmount;
        private String address;
        private String pinCode;
        private Integer estimatedArrivalMinutes;
        private Boolean isUnassigned;
        private Instant createdAt;
    }

    // --- 2. Calendar Summary Counts ---
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CalendarSummaryDto {
        private String startDate;
        private String endDate;
        private long totalBookings;
        private long instantBookings;
        private long scheduledBookings;
        private long pendingBookings;
        private long confirmedBookings;
        private long inProgressBookings;
        private long completedBookings;
        private long cancelledBookings;
        private long unassignedBookings;
    }

    // --- 3. Calendar Day Availability Overview ---
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CalendarDayAvailabilityDto {
        private String date; // YYYY-MM-DD
        private Integer dayOfWeek;
        private String dayName;
        private Boolean isOpen;
        private String openTime;
        private String closeTime;
        private Boolean isSpecialOverride;
        private String overrideType;
        private String overrideReason;
        private int totalActiveWorkers;
        private int availableSlotsCount;
        private int totalBookingsCount;
    }

    // --- 4. Working Hours Management ---
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class WorkingHoursDto {
        private UUID id;
        private UUID serviceId;
        private String serviceName;
        private Integer dayOfWeek; // 1 = Mon ... 7 = Sun
        private String dayName;
        private Boolean isOpen;
        private String openTime;      // "08:00"
        private String closeTime;     // "20:00"
        private String breakStartTime; // "13:00"
        private String breakEndTime;   // "14:00"
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SaveWorkingHoursRequest {
        private UUID serviceId; // null for global default
        @NotNull(message = "Schedules list cannot be null")
        private List<WorkingHoursDto> schedules;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CopyWorkingHoursRequest {
        private UUID serviceId;
        @NotNull(message = "Source day of week is required")
        private Integer sourceDayOfWeek;
        @NotNull(message = "Target days of week cannot be null")
        private List<Integer> targetDaysOfWeek;
    }

    // --- 5. Date Overrides Management ---
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DateOverrideDto {
        private UUID id;
        private UUID serviceId;
        private String serviceName;
        private LocalDate overrideDate;
        private OverrideType overrideType;
        private Boolean isClosed;
        private String customOpenTime;
        private String customCloseTime;
        private String blockedStartTime;
        private String blockedEndTime;
        private String reason;
        private String createdByAdminName;
        private Instant createdAt;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateDateOverrideRequest {
        private UUID serviceId;
        @NotNull(message = "Override date is required")
        private LocalDate overrideDate;
        @NotNull(message = "Override type is required")
        private OverrideType overrideType;
        @Builder.Default
        private Boolean isClosed = true;
        private String customOpenTime;
        private String customCloseTime;
        private String blockedStartTime;
        private String blockedEndTime;
        private String reason;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UpdateDateOverrideRequest {
        private OverrideType overrideType;
        private Boolean isClosed;
        private String customOpenTime;
        private String customCloseTime;
        private String blockedStartTime;
        private String blockedEndTime;
        private String reason;
    }

    // --- 6. Booking Settings Management ---
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BookingSettingsDto {
        private UUID settingId;
        private UUID serviceId;
        private String serviceName;
        private Integer slotDurationMins;
        private Integer bufferDurationMins;
        private Integer advanceBookingDays;
        private Integer minBookingNoticeMins;
        private Integer maxBookingCapacityPerSlot;
        private Boolean isActive;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UpdateBookingSettingsRequest {
        private UUID serviceId; // null for global default
        private Integer slotDurationMins;
        private Integer bufferDurationMins;
        private Integer advanceBookingDays;
        private Integer minBookingNoticeMins;
        private Integer maxBookingCapacityPerSlot;
        private Boolean isActive;
    }

    // --- 7. Slot Capacity Inspection for Admin ---
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SlotCapacityDto {
        private String timeWindow;
        private String startTime;
        private String endTime;
        private String period;
        private int maxCapacity;
        private int bookedCount;
        private int remainingCapacity;
        private boolean isAvailable;
        private boolean isDisabled;
        private String disabledReason;
        private List<String> assignedWorkerNames;
        private List<UUID> assignedBookingIds;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DateSlotsCapacityDto {
        private String date;
        private UUID serviceId;
        private String serviceName;
        private boolean isOpen;
        private String closeReason;
        private List<SlotCapacityDto> slots;
    }

    // --- 8. Worker Calendar Management ---
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class WorkerCalendarDto {
        private UUID workerId;
        private String workerName;
        private String phoneNumber;
        private Double rating;
        private Boolean isOnline;
        private Boolean isVerified;
        private List<WorkerDto.DayScheduleDto> weeklyAvailability;
        private List<WorkerDto.TimeOffResponse> leaves;
        private List<CalendarEventDto> assignedBookings;
    }

    // --- 9. Booking Reschedule & Worker Assignment ---
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RescheduleBookingRequest {
        @NotNull(message = "New scheduled start time is required")
        private Instant newScheduledAt;
        private Instant newScheduledEndAt;
        private UUID workerId; // optional: reassignment during reschedule
        private String reason; // required for audit log
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AssignWorkerRequest {
        @NotNull(message = "Worker ID is required")
        private UUID workerId;
        private String reason;
    }

    // --- 10. Audit Log ---
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AuditLogDto {
        private UUID logId;
        private UUID bookingId;
        private String entityType;
        private String entityId;
        private UUID adminId;
        private String adminName;
        private String action;
        private String previousValue;
        private String updatedValue;
        private String reason;
        private Instant createdAt;
    }
}

