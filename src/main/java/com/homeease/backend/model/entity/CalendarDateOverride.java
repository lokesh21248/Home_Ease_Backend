package com.homeease.backend.model.entity;

import com.homeease.backend.model.enums.OverrideType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "calendar_date_overrides", indexes = {
    @Index(name = "idx_calendar_overrides_date", columnList = "override_date"),
    @Index(name = "idx_calendar_overrides_type", columnList = "override_type"),
    @Index(name = "idx_calendar_overrides_service_date", columnList = "service_id, override_date")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CalendarDateOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id")
    private ServiceEntity service;

    @Column(name = "override_date", nullable = false)
    private LocalDate overrideDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "override_type", nullable = false, length = 50)
    private OverrideType overrideType;

    @Column(name = "is_closed", nullable = false)
    @Builder.Default
    private Boolean isClosed = true;

    @Column(name = "custom_open_time")
    private LocalTime customOpenTime;

    @Column(name = "custom_close_time")
    private LocalTime customCloseTime;

    @Column(name = "blocked_start_time")
    private LocalTime blockedStartTime;

    @Column(name = "blocked_end_time")
    private LocalTime blockedEndTime;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}

