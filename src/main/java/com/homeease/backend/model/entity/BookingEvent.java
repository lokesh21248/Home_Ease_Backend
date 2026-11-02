package com.homeease.backend.model.entity;

import com.homeease.backend.model.enums.BookingEventType;
import com.homeease.backend.model.enums.EventDeliveryStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "booking_events", indexes = {
        @Index(name = "idx_booking_events_booking", columnList = "booking_id, created_at DESC"),
        @Index(name = "idx_booking_events_consumed", columnList = "consumed_by, created_at DESC"),
        @Index(name = "idx_booking_events_status", columnList = "delivery_status, created_at DESC"),
        @Index(name = "idx_booking_events_type", columnList = "event_type, created_at DESC")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "event_id", updatable = false, nullable = false)
    private UUID eventId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 64)
    private BookingEventType eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "consumed_by", length = 100)
    private String consumedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", nullable = false, length = 20)
    @Builder.Default
    private EventDeliveryStatus deliveryStatus = EventDeliveryStatus.PENDING;

    @Column(name = "attempt_no", nullable = false)
    @Builder.Default
    private Integer attemptNo = 0;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
