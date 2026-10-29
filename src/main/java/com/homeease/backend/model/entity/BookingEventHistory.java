package com.homeease.backend.model.entity;

import com.homeease.backend.model.enums.BookingActorType;
import com.homeease.backend.model.enums.BookingEventType;
import com.homeease.backend.model.enums.BookingStage;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "booking_event_history", indexes = {
        @Index(name = "idx_booking_event_history_booking_occurred", columnList = "booking_id, occurred_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingEventHistory {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 64)
    private BookingEventType eventType;

    @Column(name = "schema_version", nullable = false)
    @Builder.Default
    private Integer schemaVersion = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 32)
    private BookingStage previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", length = 32)
    private BookingStage newStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 32)
    private BookingActorType actorType;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
