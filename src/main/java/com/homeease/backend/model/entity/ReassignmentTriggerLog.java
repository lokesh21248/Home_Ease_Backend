package com.homeease.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reassignment_trigger_log", indexes = {
        @Index(name = "idx_reassign_booking", columnList = "booking_id"),
        @Index(name = "idx_reassign_type_created", columnList = "trigger_type, created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReassignmentTriggerLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "trigger_id", updatable = false, nullable = false)
    private UUID triggerId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "previous_worker_id")
    private Worker previousWorker;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "new_worker_id")
    private Worker newWorker;

    @Column(name = "trigger_type", nullable = false, length = 50)
    private String triggerType;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "triggered_by", nullable = false, length = 50)
    @Builder.Default
    private String triggeredBy = "SYSTEM";

    @Column(name = "attempt_number", nullable = false)
    @Builder.Default
    private Integer attemptNumber = 1;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

