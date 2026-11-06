package com.homeease.backend.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "booking_pricing_details", indexes = {
    @Index(name = "idx_booking_pricing_details_booking", columnList = "booking_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class BookingPricingDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "pricing_detail_id", updatable = false, nullable = false)
    private UUID pricingDetailId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false, unique = true)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Booking booking;

    @Column(name = "base_service_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal baseServicePrice;

    @Column(name = "additional_charges", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal additionalCharges = BigDecimal.ZERO;

    @Column(name = "tax_amount", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(name = "discount_amount", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "coupon_code", length = 50)
    private String couponCode;

    @Column(name = "final_payable_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal finalPayableAmount;

    @Column(name = "price_breakdown_json", columnDefinition = "JSONB")
    private String priceBreakdownJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

