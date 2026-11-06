package com.homeease.backend.dto;

import com.homeease.backend.model.enums.DisputeStatus;
import com.homeease.backend.model.enums.PayoutStatus;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class AdminOpsDto {

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UpdateZoneConfigRequest {
        private Double distanceWeight;
        private Double ratingWeight;
        private Double acceptanceRateWeight;
        private Double completionRateWeight;
        private Double fairnessWeight;
        private Double round1RadiusMeters;
        private Double round2RadiusMeters;
        private Double round3RadiusMeters;
        private Double maxSearchRadiusMeters;
        private Integer offerTimeoutSeconds;
        private Integer batchAssignTMinusMinutes;
        private Double surgeMultiplier;
        private Double maxSpeedKmhThreshold;
        private Boolean isZoneActive;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ForceReassignRequest {
        @NotNull(message = "Target worker ID is required")
        private UUID targetWorkerId;

        @NotNull(message = "Reason for reassignment is required")
        private String reason;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateDisputeRequest {
        @NotNull(message = "Booking ID is required")
        private UUID bookingId;

        private UUID raisedByUserId;

        @NotNull(message = "Reason is required")
        private String reason;

        private String description;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ResolveDisputeRequest {
        @NotNull(message = "Resolution status is required")
        private DisputeStatus resolutionStatus;

        private String resolutionNotes;
        private BigDecimal refundAmount;
        private BigDecimal compensationAmount;
        private String idempotencyKey;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class BatchPayoutRequest {
        private List<UUID> workerIds;
        @NotNull(message = "Idempotency key is required")
        private String idempotencyKey;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ZoneConfigResponse {
        private UUID configId;
        private UUID serviceId;
        private String serviceName;
        private String zonePincode;
        private Double distanceWeight;
        private Double ratingWeight;
        private Double acceptanceRateWeight;
        private Double completionRateWeight;
        private Double fairnessWeight;
        private Double round1RadiusMeters;
        private Double round2RadiusMeters;
        private Double round3RadiusMeters;
        private Double maxSearchRadiusMeters;
        private Integer offerTimeoutSeconds;
        private Integer batchAssignTMinusMinutes;
        private Double surgeMultiplier;
        private Double maxSpeedKmhThreshold;
        private Boolean isZoneActive;
        private Instant updatedAt;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DisputeResponse {
        private UUID disputeId;
        private UUID bookingId;
        private UUID raisedByUserId;
        private String reason;
        private String description;
        private DisputeStatus status;
        private String resolutionNotes;
        private BigDecimal refundAmount;
        private BigDecimal compensationAmount;
        private UUID resolvedByAdminId;
        private Instant resolvedAt;
        private Instant createdAt;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class WorkerPayoutResponse {
        private UUID batchId;
        private UUID workerId;
        private String workerName;
        private BigDecimal amount;
        private PayoutStatus status;
        private String razorpayPayoutId;
        private String failureReason;
        private Instant settledAt;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PendingPayoutWorker {
        private UUID workerId;
        private String workerName;
        private String phone;
        private BigDecimal payableBalance;
        private String bankAccountLinked;
    }
}

