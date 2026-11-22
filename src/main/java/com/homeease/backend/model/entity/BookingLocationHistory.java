package com.homeease.backend.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One sampled GPS breadcrumb of the partner travelling to a booking (table {@code booking_location_history},
 * migration V27). Plain {@code booking_id} column rather than a {@code @ManyToOne}: history rows are written in
 * bulk from a background thread and must never load or lock the booking.
 */
@Entity
@Table(name = "booking_location_history", indexes = {
        @Index(name = "idx_booking_location_history_booking_time", columnList = "booking_id, recorded_at"),
        @Index(name = "idx_booking_location_history_received", columnList = "received_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingLocationHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "worker_id")
    private UUID workerId;

    @Column(name = "lat", nullable = false)
    private double lat;

    @Column(name = "lng", nullable = false)
    private double lng;

    @Column(name = "accuracy_m")
    private Float accuracyM;

    @Column(name = "speed_mps")
    private Float speedMps;

    @Column(name = "heading_deg")
    private Float headingDeg;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
}
