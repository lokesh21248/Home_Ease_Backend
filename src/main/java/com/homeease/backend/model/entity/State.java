package com.homeease.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "states", indexes = {
    @Index(name = "idx_states_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class State {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "state_id", updatable = false, nullable = false)
    private UUID stateId;

    /**
     * Display name (e.g. "Telangana").
     */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /**
     * Normalised upper-case name for dedup constraint (e.g. "TELANGANA").
     * Always computed from name before save — never set by clients directly.
     */
    @Column(name = "name_norm", nullable = false, length = 100)
    private String nameNorm;

    /**
     * Only active states participate in serviceability checks.
     * Defaults to FALSE — must be explicitly enabled.
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
