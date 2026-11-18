package com.homeease.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    // The column is JSONB (V14). Mapped like BookingEvent/BookingEventOutbox.payload so the write works whether or not
    // the JDBC URL carries stringtype=unspecified: without a JSON mapping PostgreSQL rejects the insert ("column is of
    // type jsonb but expression is of type character varying"), and because the insert is only sent at commit it is not
    // caught by the audit code's try/catch but rolls back the whole dispatch transaction (no offers for the booking).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rejected_worker_reasons", columnDefinition = "jsonb")
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

