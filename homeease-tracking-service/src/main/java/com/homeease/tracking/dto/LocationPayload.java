package com.homeease.tracking.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

/**
 * Location event DTO – shared between partner GPS ingestion and customer delivery.
 *
 * Field notes:
 *  - sequenceNumber: server-assigned monotonic counter per bookingId. Clients must
 *    ignore or discard frames whose sequenceNumber is <= the last seen value to
 *    guarantee correct ordering even when network packets arrive out of order.
 *  - accuracy: GPS HDOP in metres. Frames with accuracy > the configured threshold
 *    (default 50 m) are rejected upstream in LiveTrackingService.
 *  - bearing: compass heading 0-360°. Use for marker rotation on customer map.
 *  - speed: metres per second from the device GPS fix.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LocationPayload {
    @JsonAlias({"partnerId", "workerId"})
    private String partnerId;
    private String workerId;
    private String bookingId;
    private Double lat;
    private Double lng;
    private Double bearing;         // Heading in degrees (0–360) for marker rotation
    @JsonAlias({"speedMetersPerSecond", "speed"})
    private Double speed;           // Speed in m/s (as reported by device GPS)
    @JsonAlias({"accuracyMeters", "accuracy"})
    private Double accuracy;        // GPS horizontal accuracy in metres
    private Double distanceKm;      // Server-computed distance remaining to customer
    private Integer etaMinutes;     // Server-computed ETA in minutes
    private Long timestamp;         // Device timestamp (ms epoch), server-clamped
    private Long serverTimestamp;   // Server receipt time (ms epoch)
    /** Monotonic sequence counter assigned by the server, per bookingId. */
    private Long sequenceNumber;
    @Builder.Default
    private Boolean trackingActive = true;
    private String status;          // "TRACKING" | "COMPLETED" | "CANCELLED"
    private String message;

    @JsonIgnore
    public String getEffectiveId() {
        if (partnerId != null && !partnerId.isBlank()) return partnerId;
        return workerId != null ? workerId : "unknown_partner";
    }
}
