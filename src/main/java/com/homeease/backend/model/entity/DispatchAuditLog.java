package com.homeease.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dispatch_audit_logs", indexes = {
    @Index(name = "idx_dispatch_audit_logs_booking", columnList = "booking_id"),
    @Index(name = "idx_dispatch_audit_logs_created", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DispatchAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "log_id", updatable = false, nullable = false)
    private UUID logId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @Column(name = "service_id")
    private UUID serviceId;

    @Column(name = "customer_lat")
    private Double customerLat;

    @Column(name = "customer_lng")
    private Double customerLng;

    @Column(name = "search_radius_meters", nullable = false)
    @Builder.Default
    private Double searchRadiusMeters = 5000.0;

    @Column(name = "eligible_workers_count", nullable = false)
    @Builder.Default
    private Integer eligibleWorkersCount = 0;

    @Column(name = "rejected_worker_reasons", columnDefinition = "TEXT")
    private String rejectedWorkerReasons;

    @Column(name = "selected_worker_id")
    private UUID selectedWorkerId;

    @Column(name = "kafka_status", length = 50)
    private String kafkaStatus;

    @Column(name = "websocket_status", length = 50)
    private String websocketStatus;

    @Column(name = "fcm_status", length = 50)
    private String fcmStatus;

    @Column(name = "assignment_status", length = 50)
    private String assignmentStatus;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

