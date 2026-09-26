package com.homeease.backend.model.entity;

import com.homeease.backend.model.enums.PricingModel;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sub_services")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubService {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "sub_service_id", updatable = false, nullable = false)
    private UUID subServiceId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private ServiceEntity service;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "pricing_type", nullable = false)
    @Builder.Default
    private PricingModel pricingType = PricingModel.FIXED;

    @Column(name = "base_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal basePrice;

    @Column(name = "unit_label", length = 30)
    private String unitLabel;

    @Column(name = "estimated_mins", nullable = false)
    private Integer estimatedMins;

    @Column(name = "image_url", nullable = false, columnDefinition = "TEXT")
    private String imageUrl;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
