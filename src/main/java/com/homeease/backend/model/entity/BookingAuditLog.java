package com.homeease.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "booking_audit_logs", indexes = {
    @Index(name = "idx_booking_audit_logs_booking", columnList = "booking_id, created_at DESC"),
    @Index(name = "idx_booking_audit_logs_entity", columnList = "entity_type, created_at DESC"),
    @Index(name = "idx_booking_audit_logs_admin", columnList = "admin_id, created_at DESC")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "log_id", updatable = false, nullable = false)
    private UUID logId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id")
    private Booking booking;

    @Column(name = "entity_type", nullable = false, length = 50)
    @Builder.Default
    private String entityType = "BOOKING";

    @Column(name = "entity_id", length = 100)
    private String entityId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "admin_id")
    private User admin;

    @Column(name = "admin_name", length = 100)
    private String adminName;

    @Column(name = "action", nullable = false, length = 100)
    private String action;

    @Column(name = "previous_value", columnDefinition = "TEXT")
    private String previousValue;

    @Column(name = "updated_value", columnDefinition = "TEXT")
    private String updatedValue;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

