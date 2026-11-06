package com.homeease.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public class SlotHoldDto {

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class HoldRequest {
        @NotNull(message = "Service ID is required")
        private UUID serviceId;

        @NotNull(message = "Slot date is required")
        private LocalDate date;

        @NotBlank(message = "Slot time is required (e.g., 10:00 or 10:00 - 11:00)")
        private String slotTime;

        @NotBlank(message = "Pincode is required")
        private String pincode;

        private UUID userId;

        private String idempotencyKey;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class HoldResponse {
        private UUID holdId;
        private String lockToken;
        private String windowKey;
        private UUID serviceId;
        private LocalDate slotDate;
        private String startTime;
        private String endTime;
        private Instant expiresAt;
        private int remainingCapacity;
        private String message;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SlotWindow {
        private String windowKey;
        private String timeSlot;
        private String startTime;
        private String endTime;
        private int maxCapacity;
        private int availableCapacity;
        private boolean available;
    }
}

