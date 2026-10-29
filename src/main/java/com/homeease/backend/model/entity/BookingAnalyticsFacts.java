package com.homeease.backend.model.entity;

import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.TransactionState;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "booking_analytics_booking_facts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingAnalyticsFacts {

    @Id
    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(name = "service_id")
    private UUID serviceId;

    @Column(name = "service_pincode", length = 6)
    private String servicePincode;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_status", length = 32)
    private BookingStage currentStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", length = 32)
    private TransactionState paymentStatus;

    @Column(name = "create_to_accept_ms")
    private Long createToAcceptMs;

    @Column(name = "accept_to_start_ms")
    private Long acceptToStartMs;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
