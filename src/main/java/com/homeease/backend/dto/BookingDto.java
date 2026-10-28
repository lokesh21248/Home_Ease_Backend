package com.homeease.backend.dto;

import com.homeease.backend.model.enums.BookingStage;
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
        @Future(message = "Scheduled time must be in the future")
        private Instant scheduledAt;
        @NotNull(message = "User latitude is required")
        private Double userLat;
        @NotNull(message = "User longitude is required")
        private Double userLng;
        @NotNull(message = "Payment method is required")
        private PaymentMode paymentMethod;
        /**
         * 6-digit Indian pincode of the service address.
         * The backend revalidates serviceability at transaction time
         * before creating the booking. An area deactivated after the frontend check
         * will be caught here.
         */
        @NotBlank(message = "Service address pincode is required")
        @Pattern(regexp = "^[0-9]{6}$", message = "Pincode must be exactly 6 digits")
        private String pincode;

        /**
         * Optional saved address ID to verify ownership.
         */
        private String addressId;

        // ─── Friend / third-party booking fields (V8) ─────────────────────

        /**
         * True when the customer is booking on behalf of someone else.
         * Omit (or send false) for a normal self-booking — backward compatible.
         */
        private Boolean isForFriend;

        /**
         * Full name of the service recipient.
         * Required by the backend when isForFriend = true.
         */
        @Size(max = 100, message = "Recipient name must be 100 characters or fewer")
        private String recipientName;

        /**
         * Mobile number of the service recipient.
         * Required by the backend when isForFriend = true.
         * Accepts 10-digit numbers with optional +91 or 0 prefix.
         */
        @Pattern(
            regexp = "^(?:\\+91|0)?[6-9]\\d{9}$",
            message = "Recipient phone must be a valid 10-digit Indian mobile number"
        )
        private String recipientPhone;

        /**
         * Human-readable service address of the recipient
         * (e.g. "Flat 4B, Banjara Hills, Hyderabad"). Optional.
         */
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
        private String pinCode; // 4-digit worker unlock PIN
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

        // ─── Friend / third-party booking fields (V8) ─────────────────
        /** True when the booking was placed on behalf of another person. */
        private Boolean isForFriend;
        /** Full name of the service recipient; null for self-bookings. */
        private String  recipientName;
        /** Mobile of the service recipient; returned to booking creator only. */
        private String  recipientPhone;
        /** Human-readable service address of the recipient. */
        private String  recipientAddress;
        /** 6-digit service-area pincode validated at booking creation time. */
        private String  servicePincode;
    }
}
