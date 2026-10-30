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
     * Multiple localities can be associated with the same pincode.
     */
    @Column(name = "pincode", nullable = false, length = 6)
    private String pincode;

    /** Optional parent city for metadata / future expansion. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "city_id", nullable = true)
    private City city;

    /**
     * Optional state FK for metadata / future expansion.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "state_id", nullable = true)
    private State state;

    /** Optional human-readable locality / area label (e.g. "Banjara Hills"). */
    @Column(name = "area_name", length = 150)
    private String areaName;

    /**
     * Serviceability is determined directly by this flag for the pincode.
     * Defaults to FALSE — must be explicitly activated by the admin.
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
