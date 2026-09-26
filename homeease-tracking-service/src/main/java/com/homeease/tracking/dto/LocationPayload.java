package com.homeease.tracking.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LocationPayload {
    private String workerId;
    private String bookingId;
    private Double lat;
    private Double lng;
    private Long timestamp;
}
