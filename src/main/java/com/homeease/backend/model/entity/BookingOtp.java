package com.homeease.backend.model.entity;

import com.homeease.backend.model.enums.OtpStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "booking_otp", indexes = {
        @Index(name = "idx_booking_otp_booking", columnList = "booking_id"),
        @Index(name = "idx_booking_otp_expires", columnList = "expires_at"),
        @Index(name = "idx_booking_otp_status", columnList = "status"),
        @Index(name = "idx_booking_otp_booking_status", columnList = "booking_id, status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingOtp {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "otp_id", updatable = false, nullable = false)
    private UUID otpId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    /** SHA-256 hash only. Never stored in plaintext. */
    @Column(name = "hashed_otp", nullable = false, length = 64)
    private String hashedOtp;

    /** Prefixed hash of the customer's registered phone (non-reversible). */
    @Column(name = "phone_hash", nullable = false, length = 64)
    private String phoneHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "max_attempts", nullable = false)
    @Builder.Default
    private Integer maxAttempts = 5;

    @Column(name = "attempts", nullable = false)
    @Builder.Default
    private Integer attempts = 0;

    @Column(name = "resend_cooldown_until")
    private Instant resendCooldownUntil;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private OtpStatus status = OtpStatus.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;
}
