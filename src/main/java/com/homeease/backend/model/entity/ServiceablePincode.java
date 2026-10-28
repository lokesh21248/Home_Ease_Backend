package com.homeease.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "serviceable_pincodes", indexes = {
    @Index(name = "idx_pincodes_pincode",   columnList = "pincode"),
    @Index(name = "idx_pincodes_city_id",   columnList = "city_id"),
    @Index(name = "idx_pincodes_state_id",  columnList = "state_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ServiceablePincode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "pincode_id", updatable = false, nullable = false)
    private UUID pincodeId;

    /**
     * Indian 6-digit pincode stored as VARCHAR — preserves leading zeros
     * and matches the DB CHECK constraint '^[0-9]{6}$'.
     */
    @Column(name = "pincode", nullable = false, length = 6, unique = true)
    private String pincode;

    /** Parent city. ON DELETE RESTRICT. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "city_id", nullable = false)
    private City city;

    /**
     * Denormalized state FK — allows single-table serviceability check
     * without an extra JOIN.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "state_id", nullable = false)
    private State state;

    /** Optional human-readable area label (e.g. "Banjara Hills"). */
    @Column(name = "area_name", length = 150)
    private String areaName;

    /**
     * A pincode is serviceable only when this flag AND city.isActive AND
     * state.isActive are all true. Defaults to FALSE — must be explicitly
     * activated by the admin.
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
