package com.homeease.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.BookingType;
import com.homeease.backend.model.enums.PaymentMode;
import com.homeease.backend.model.enums.TransactionState;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CreateBookingRequest {
        @NotNull(message = "Service ID is required")
        private UUID serviceId;
        @NotNull(message = "Sub-services list cannot be empty")
        private List<SubServiceRequestItem> subServices;
        private List<AddonRequestItem> addons;
        private String couponCode;

        private BookingType bookingType; // Defaults to INSTANT if scheduledAt is null/near-now

        private Instant scheduledAt;
        private Instant scheduledEndAt;

        @NotNull(message = "User latitude is required")
        private Double userLat;
        @NotNull(message = "User longitude is required")
        private Double userLng;
        @NotNull(message = "Payment method is required")
        private PaymentMode paymentMethod;

        @NotBlank(message = "Service address pincode is required")
        @Pattern(regexp = "^[0-9]{6}$", message = "Pincode must be exactly 6 digits")
        private String pincode;

        private String addressId;

        // Friend / third-party booking fields
        private Boolean isForFriend;
        @Size(max = 100, message = "Recipient name must be 100 characters or fewer")
        private String recipientName;
        @Pattern(regexp = "^(?:$|(?:\\+91|0)?[6-9]\\d{9})$", message = "Recipient phone must be a valid 10-digit Indian mobile number")
        private String recipientPhone;
        private String recipientAddress;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class InstantBookingRequest {
        @NotNull(message = "Service ID is required")
        private UUID serviceId;
        @NotNull(message = "Sub-services list cannot be empty")
        private List<SubServiceRequestItem> subServices;
        private List<AddonRequestItem> addons;
        private String couponCode;

        @NotNull(message = "User latitude is required")
        private Double userLat;
        @NotNull(message = "User longitude is required")
        private Double userLng;
        @NotNull(message = "Payment method is required")
        private PaymentMode paymentMethod;

        @NotBlank(message = "Pincode is required")
        @Pattern(regexp = "^[0-9]{6}$", message = "Pincode must be exactly 6 digits")
        private String pincode;

        private String addressId;
        private Boolean isForFriend;
        private String recipientName;
        private String recipientPhone;
        private String recipientAddress;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ScheduledBookingRequest {
        @NotNull(message = "Service ID is required")
        private UUID serviceId;
        @NotNull(message = "Sub-services list cannot be empty")
        private List<SubServiceRequestItem> subServices;
        private List<AddonRequestItem> addons;
        private String couponCode;

        @NotNull(message = "Scheduled date is required (YYYY-MM-DD)")
        private LocalDate scheduledDate;

        @NotBlank(message = "Selected time slot is required (e.g. '09:00 AM - 10:00 AM' or '09:00')")
        private String timeSlot;

        @NotNull(message = "User latitude is required")
        private Double userLat;
        @NotNull(message = "User longitude is required")
        private Double userLng;
        @NotNull(message = "Payment method is required")
        private PaymentMode paymentMethod;

        @NotBlank(message = "Pincode is required")
        @Pattern(regexp = "^[0-9]{6}$", message = "Pincode must be exactly 6 digits")
        private String pincode;

        private String addressId;
        private Boolean isForFriend;
        private String recipientName;
        private String recipientPhone;
        private String recipientAddress;
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
    public static class CancelBookingRequest {
        @NotBlank(message = "Cancellation reason is required")
        private String reason;
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
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AvailableSlotDto {
        private String time; // e.g. "09:00 AM - 10:00 AM"
        private String startTime; // "09:00"
        private String endTime; // "10:00"
        private Boolean available;
        private String period; // "MORNING", "AFTERNOON", "EVENING"
        private Integer availableWorkersCount;
        private String reason; // Optional reason if unavailable
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AvailableDateDto {
        private String date; // "2026-11-02"
        private String dayName; // "Mon"
        private String monthName; // "Nov"
        private Integer dayOfMonth; // 2
        private Boolean available;
        private Integer availableSlotsCount;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class NearbyWorkerDto {
        private UUID workerId;
        private String fullName;
        private Double rating;
        private Double distanceMeters;
        private Integer estimatedArrivalMinutes;
        private String avatarUrl;
        private Boolean isOnline;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BookingResponse {
        private UUID bookingId;
        private UUID userId;
        private String userName;
        private String userPhone;
        private UUID workerId;
        private String workerName;
        private String workerPhone;
        private String workerAvatarUrl;
        private Double workerRating;
        private UUID serviceId;
        private String serviceName;
        private BookingType bookingType;
        private BookingStage status;
        private String pinCode; // 4-digit worker unlock PIN
        private Instant scheduledAt;
        private Instant scheduledEndAt;
        private Instant startedAt;
        private Instant completedAt;
        private Instant workerAssignedAt;
        private Integer estimatedArrivalMinutes;
        private String cancellationReason;
        private Instant cancelledAt;
        private Double userLat;
        private Double userLng;
        private BigDecimal baseAmount;
        private BigDecimal extraAmount;
        private BigDecimal discountAmount;
        private BigDecimal totalAmount;
        private TransactionState paymentStatus;
        private PaymentMode paymentMethod;
        private Instant createdAt;

        // Friend / third-party booking fields
        private Boolean isForFriend;
        private String  recipientName;
        private String  recipientPhone;
        private String  recipientAddress;
        private String  servicePincode;
    }
}
