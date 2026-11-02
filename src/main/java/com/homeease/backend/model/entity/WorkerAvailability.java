package com.homeease.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "worker_availability", uniqueConstraints = {
    @UniqueConstraint(name = "uq_worker_day_of_week", columnNames = {"worker_id", "day_of_week"})
}, indexes = {
    @Index(name = "idx_worker_avail_lookup", columnList = "worker_id, day_of_week, is_available")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkerAvailability {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "availability_id", updatable = false, nullable = false)
    private UUID availabilityId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "worker_id", nullable = false)
    private Worker worker;

    /**
     * 1 = Monday, 2 = Tuesday, ..., 7 = Sunday (ISO-8601 day-of-week).
     */
    @Column(name = "day_of_week", nullable = false)
    private Integer dayOfWeek;

    @Column(name = "is_available", nullable = false)
    @Builder.Default
    private Boolean isAvailable = true;

    @Column(name = "start_time", nullable = false)
    @Builder.Default
    private LocalTime startTime = LocalTime.of(8, 0);

    @Column(name = "end_time", nullable = false)
    @Builder.Default
    private LocalTime endTime = LocalTime.of(20, 0);

    @Column(name = "break_start_time")
    @Builder.Default
    private LocalTime breakStartTime = LocalTime.of(13, 0);

    @Column(name = "break_end_time")
    @Builder.Default
    private LocalTime breakEndTime = LocalTime.of(14, 0);

    @Column(name = "slot_duration_mins", nullable = false)
    @Builder.Default
    private Integer slotDurationMins = 60;

    @Column(name = "max_bookings_per_slot", nullable = false)
    @Builder.Default
    private Integer maxBookingsPerSlot = 1;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}

