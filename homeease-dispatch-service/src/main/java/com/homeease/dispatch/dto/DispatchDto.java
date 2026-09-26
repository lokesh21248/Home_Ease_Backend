package com.homeease.dispatch.dto;

import lombok.*;

import java.util.UUID;

public class DispatchDto {

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TriggerDispatchRequest {
        private UUID bookingId;
        private UUID subServiceId;
        private Double userLat;
        private Double userLng;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AcceptJobRequest {
        private UUID bookingId;
        private UUID workerUserId;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RejectJobRequest {
        private UUID bookingId;
        private UUID workerUserId;
        private UUID subServiceId;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DispatchResultResponse {
        private UUID bookingId;
        private String status;
        private UUID assignedWorkerId;
        private String pinCode;
        private String message;
    }
}
