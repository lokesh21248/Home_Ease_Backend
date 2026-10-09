package com.homeease.tracking.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LocationPayload {
    @JsonAlias({"partnerId", "workerId"})
    private String partnerId;
    private String workerId;
    private String bookingId;
    private Double lat;
    private Double lng;
    private Double bearing;      // Heading in degrees (0 - 360) for marker icon rotation
    private Double speed;        // Speed in m/s or km/h
    private Double accuracy;     // GPS accuracy in meters
    private Double distanceKm;   // Distance remaining to customer
    private Integer etaMinutes;  // Estimated arrival time in minutes
    private Long timestamp;

    public String getEffectiveId() {
        if (partnerId != null && !partnerId.isBlank()) return partnerId;
        return workerId != null ? workerId : "unknown_partner";
    }
}
