package com.homeease.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.homeease.backend.model.enums.TrackingSessionStatus;
import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class TrackingDto {

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TrackingSessionDto {
        private String sessionId;
        private UUID bookingId;
        private UUID workerUserId;
        private UUID customerUserId;
        private TrackingSessionStatus status;
        private Boolean trackingActive;
        private Double lastLat;
        private Double lastLng;
        private Double bearing;
        private Double speed;
        private Double accuracy;
        private Long lastTimestamp;
        private Instant createdAt;
        private Instant startedAt;
        private Instant arrivedAt;
        private Instant stoppedAt;
        private String stopReason;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TrackingLocationResponse {
        private String bookingId;
        private Double latitude;
        private Double longitude;
        private Double lat;
        private Double lng;
        private Double bearing;
        private Double heading;
        private Double speed;
        private Double accuracy;
        private Long timestamp;
        private Boolean trackingActive;
        private String status;
        private String workerName;
        private String distanceText;
        private String etaText;
        private Double distanceKm;
        private Integer etaMinutes;
        private Boolean found;
        private String message;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TrackingDistanceResponse {
        private String bookingId;
        private Double distanceKm;
        private Long distanceMeters;
        private Integer etaMinutes;
        private String distanceText;
        private String etaText;
        private Boolean found;
        private Boolean trackingActive;
        private LatLng workerLocation;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class LatLng {
        private Double lat;
        private Double lng;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TrackingRouteResponse {
        private List<double[]> coordinates; // [lat, lng]
        private Double distanceKm;
        private Integer durationMin;
        private Boolean found;
        private String message;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LocationUpdateResult {
        private boolean success;
        private String status;
        private boolean trackingActive;
        private String message;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TrackingEventMessage {
        private String type; // WORKER_LOCATION_UPDATED, WORKER_ARRIVED, BOOKING_TRACKING_STOPPED, etc.
        private String bookingId;
        private String status;
        private String trackingStatus;
        private boolean trackingActive;
        private Double lat;
        private Double lng;
        private Double bearing;
        private Double speed;
        private Double accuracy;
        private Long timestamp;
        private String message;
        private String reason;
    }
}

