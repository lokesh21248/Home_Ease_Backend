package com.homeease.backend.dto;

import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.DiscountModel;
import com.homeease.backend.model.enums.NotifyStatus;
import com.homeease.backend.model.enums.TransactionState;
import com.homeease.backend.model.enums.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class AdminDto {

    // --- Banners DTOs ---
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class BannerRequest {
        @NotBlank(message = "Title is required")
        private String title;
        @NotBlank(message = "Image URL is required")
        private String imageUrl;
        private String targetType;
        private UUID targetId;
        private Boolean isActive;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class BannerPatchRequest {
        private String title;
        private String imageUrl;
        private String targetType;
        private UUID targetId;
        private Boolean isActive;
    }

    // --- Coupons DTOs ---
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CouponRequest {
        @NotBlank(message = "Coupon code is required")
        private String code;
        @NotNull(message = "Discount type is required")
        private DiscountModel discountType;
        @NotNull(message = "Discount value is required")
        private BigDecimal discountVal;
        private BigDecimal minOrderValue;
        private BigDecimal maxDiscountAmount;
        @NotNull(message = "Valid from timestamp is required")
        private Instant validFrom;
        @NotNull(message = "Valid until timestamp is required")
        private Instant validUntil;
        private Integer usageLimit;
        private Boolean isActive;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CouponPatchRequest {
        private String code;
        private DiscountModel discountType;
        private BigDecimal discountVal;
        private BigDecimal minOrderValue;
        private BigDecimal maxDiscountAmount;
        private Instant validFrom;
        private Instant validUntil;
        private Integer usageLimit;
        private Boolean isActive;
    }

    // --- Payments DTOs ---
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PaymentResponse {
        private UUID paymentId;
        private UUID bookingId;
        private String customerName;
        private String customerPhone;
        private String transactionRef;
        private BigDecimal grossAmount;
        private BigDecimal platformCommissionPercent;
        private BigDecimal platformCommissionAmount;
        private BigDecimal workerPayoutAmount;
        private TransactionState status;
        private Instant createdAt;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PaymentStatusUpdateRequest {
        @NotNull(message = "New transaction state is required")
        private TransactionState status;
    }

    // --- Notifications DTOs ---
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class InstantNotificationRequest {
        private UUID recipientUserId; // If null, broadcasts to topic "all_users"
        @NotBlank(message = "Notification title is required")
        private String title;
        @NotBlank(message = "Notification message body is required")
        private String body;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ScheduledNotificationRequest {
        private UUID recipientUserId;
        private UUID recipientWorkerId;
        @NotBlank(message = "Title is required")
        private String title;
        @NotBlank(message = "Message body is required")
        private String message;
        @NotBlank(message = "Type is required")
        private String type;
        @NotNull(message = "Scheduled timestamp is required")
        private Instant scheduledAt;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class NotificationResponse {
        private UUID notificationId;
        private UUID userId;
        private String recipientName;
        private UUID workerId;
        private String title;
        private String message;
        private String type;
        private Instant scheduledAt;
        private Instant sentAt;
        private NotifyStatus status;
        private Boolean isRead;
        private Instant createdAt;
    }

    // --- Live Location DTOs ---
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class LiveWorkerLocationResponse {
        private UUID workerId;
        private UUID userId;
        private String fullName;
        private String phoneNumber;
        private Boolean isOnline;
        private Boolean isVerified;
        private Double currentLat;
        private Double currentLng;
        private Instant lastUpdated;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class LiveCustomerLocationResponse {
        private UUID bookingId;
        private UUID userId;
        private String customerName;
        private String customerPhone;
        private String serviceName;
        private BookingStage bookingStatus;
        private Double userLat;
        private Double userLng;
        private UUID assignedWorkerId;
        private String assignedWorkerName;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class LiveMapOverviewResponse {
        private List<LiveWorkerLocationResponse> activeWorkers;
        private List<LiveCustomerLocationResponse> activeBookings;
        private long totalOnlineWorkers;
        private long totalActiveBookings;
    }

    // --- User & Worker Management DTOs ---
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UserResponse {
        private UUID userId;
        private String fullName;
        private String phoneNumber;
        private String email;
        private UserRole role;
        private Instant createdAt;
        private Boolean isWorker;
        private Boolean isWorkerVerified;
        private Boolean isWorkerBlocked;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UserRoleUpdateRequest {
        @NotNull(message = "Role is required")
        private UserRole role;
    }
}
