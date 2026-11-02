package com.homeease.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "booking_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "setting_id", updatable = false, nullable = false)
    private UUID settingId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id")
    private ServiceEntity service;

    @Column(name = "slot_duration_mins", nullable = false)
    @Builder.Default
    private Integer slotDurationMins = 60;

    @Column(name = "buffer_duration_mins", nullable = false)
    @Builder.Default
    private Integer bufferDurationMins = 15;

    @Column(name = "advance_booking_days", nullable = false)
    @Builder.Default
    private Integer advanceBookingDays = 14;

    @Column(name = "min_booking_notice_mins", nullable = false)
    @Builder.Default
    private Integer minBookingNoticeMins = 30;

    @Column(name = "max_booking_capacity_per_slot", nullable = false)
    @Builder.Default
    private Integer maxBookingCapacityPerSlot = 10;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}

