package com.homeease.backend.model.entity;

import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.PaymentMode;
import com.homeease.backend.model.enums.TransactionState;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.locationtech.jts.geom.Point;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bookings", indexes = {
    @Index(name = "idx_bookings_user_created", columnList = "user_id, created_at DESC"),
    @Index(name = "idx_bookings_worker_created", columnList = "worker_id, created_at DESC"),
    @Index(name = "idx_bookings_worker_status", columnList = "worker_id, status"),
    @Index(name = "idx_bookings_status", columnList = "status"),
    @Index(name = "idx_bookings_scheduled_at", columnList = "scheduled_at"),
    @Index(name = "idx_bookings_payment_status", columnList = "payment_status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "booking_id", updatable = false, nullable = false)
    private UUID bookingId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "worker_id")
    private Worker worker;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private ServiceEntity service;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "coupon_id")
    private Coupon coupon;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private BookingStage status = BookingStage.SEARCHING;

    @Column(name = "pin_code", nullable = false, length = 4)
    private String pinCode;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "user_lat", nullable = false)
    private Double userLat;

    @Column(name = "user_lng", nullable = false)
    private Double userLng;

    @Transient
    private Point userLocation;

    @Column(name = "base_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal baseAmount;

    @Column(name = "extra_amount", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal extraAmount = BigDecimal.ZERO;

    @Column(name = "discount_amount", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false)
    @Builder.Default
    private TransactionState paymentStatus = TransactionState.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false)
    private PaymentMode paymentMethod;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * P0-2 — Optional client-supplied idempotency key (max 64 chars).
     * When provided, a duplicate request with the same key returns the
     * existing booking instead of inserting a new row.
     */
    @Column(name = "idempotency_key", unique = true, length = 64)
    private String idempotencyKey;

    // ─── Friend / third-party booking fields (V8) ────────────────────

    /**
     * TRUE when the logged-in customer is booking on behalf of another
     * person (friend, family member, etc.). Defaults to FALSE so all
     * existing self-booking rows are unaffected.
     */
    @Column(name = "is_for_friend", nullable = false)
    @Builder.Default
    private Boolean isForFriend = false;

    /** Full name of the service recipient. Null for self-bookings. */
    @Column(name = "recipient_name", length = 100)
    private String recipientName;

    /** Mobile number of the service recipient. Null for self-bookings. */
    @Column(name = "recipient_phone", length = 20)
    private String recipientPhone;

    /**
     * Human-readable service address of the recipient
     * (e.g. "Flat 4B, Banjara Hills, Hyderabad"). Null for self-bookings.
     */
    @Column(name = "recipient_address", columnDefinition = "TEXT")
    private String recipientAddress;

    /**
     * 6-digit service-area pincode validated by assertServiceable() at
     * booking-creation time. Null for bookings created before the V8
     * migration (backward compatible).
     */
    @Column(name = "service_pincode", length = 6)
    private String servicePincode;
}
