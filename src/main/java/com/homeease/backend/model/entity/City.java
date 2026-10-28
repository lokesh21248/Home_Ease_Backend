package com.homeease.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cities", indexes = {
    @Index(name = "idx_cities_state_id",  columnList = "state_id"),
    @Index(name = "idx_cities_active",    columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class City {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "city_id", updatable = false, nullable = false)
    private UUID cityId;

    /**
     * Parent state. ON DELETE RESTRICT prevents orphan cities.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "state_id", nullable = false)
    private State state;

    /** Display name (e.g. "Hyderabad"). */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** Normalised upper-case name for per-state dedup (e.g. "HYDERABAD"). */
    @Column(name = "name_norm", nullable = false, length = 100)
    private String nameNorm;

    /**
     * Only active cities under active states are serviceable.
     * Defaults to FALSE.
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
