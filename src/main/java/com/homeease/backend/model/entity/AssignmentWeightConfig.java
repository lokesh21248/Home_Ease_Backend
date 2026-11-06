package com.homeease.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "assignment_weight_config", indexes = {
        @Index(name = "idx_assign_weight_svc_pin", columnList = "service_id, zone_pincode")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssignmentWeightConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "config_id", updatable = false, nullable = false)
    private UUID configId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id")
    private ServiceEntity service;

    @Column(name = "zone_pincode", length = 20)
    private String zonePincode;

    @Column(name = "distance_weight", nullable = false)
    @Builder.Default
    private Double distanceWeight = 0.35;

    @Column(name = "rating_weight", nullable = false)
    @Builder.Default
    private Double ratingWeight = 0.25;

    @Column(name = "acceptance_rate_weight", nullable = false)
    @Builder.Default
    private Double acceptanceRateWeight = 0.20;

    @Column(name = "completion_rate_weight", nullable = false)
    @Builder.Default
    private Double completionRateWeight = 0.10;

    @Column(name = "fairness_weight", nullable = false)
    @Builder.Default
    private Double fairnessWeight = 0.10;

    @Column(name = "max_search_radius_meters", nullable = false)
    @Builder.Default
    private Double maxSearchRadiusMeters = 8000.0;

    @Column(name = "offer_timeout_seconds", nullable = false)
    @Builder.Default
    private Integer offerTimeoutSeconds = 60;

    @Column(name = "batch_assign_t_minus_minutes", nullable = false)
    @Builder.Default
    private Integer batchAssignTMinusMinutes = 120;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}

