package com.homeease.backend.repository;

import com.homeease.backend.model.entity.BookingOtp;
import com.homeease.backend.model.enums.OtpStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingOtpRepository extends JpaRepository<BookingOtp, UUID> {

    Optional<BookingOtp> findFirstByBooking_BookingIdOrderByCreatedAtDesc(UUID bookingId);

    List<BookingOtp> findAllByBooking_BookingId(UUID bookingId);

    List<BookingOtp> findByStatus(OtpStatus status);

    List<BookingOtp> findByStatusAndExpiresAtBefore(OtpStatus status, Instant now);

    Optional<BookingOtp> findFirstByBooking_BookingIdAndStatus(UUID bookingId, OtpStatus status);

    /**
     * Atomically spends one verification attempt. Returns 1 only if the OTP is still ACTIVE and had an attempt
     * left, so N parallel guesses can never exceed {@code maxAttempts} (a read-modify-write on the entity could).
     * Bulk JPQL on purpose: nothing here may be overwritten later by a stale in-memory copy of the entity.
     */
    @Modifying
    @Query("UPDATE BookingOtp o SET o.attempts = o.attempts + 1 "
            + "WHERE o.otpId = :otpId AND o.status = com.homeease.backend.model.enums.OtpStatus.ACTIVE "
            + "AND o.attempts < o.maxAttempts")
    int consumeAttempt(@Param("otpId") UUID otpId);

    /** Moves an ACTIVE OTP to a terminal status; {@code lockedUntil} doubles as the lockout deadline for REVOKED. */
    @Modifying
    @Query("UPDATE BookingOtp o SET o.status = :status, o.resendCooldownUntil = :lockedUntil "
            + "WHERE o.otpId = :otpId AND o.status = com.homeease.backend.model.enums.OtpStatus.ACTIVE")
    int closeActive(@Param("otpId") UUID otpId, @Param("status") OtpStatus status,
                    @Param("lockedUntil") Instant lockedUntil);

    /** Fresh value straight from the database (a scalar projection is never served from the persistence context). */
    @Query("SELECT o.attempts FROM BookingOtp o WHERE o.otpId = :otpId")
    Integer currentAttempts(@Param("otpId") UUID otpId);

    @Modifying
    @Query("UPDATE BookingOtp o SET o.status = :status, o.updatedAt = :now WHERE o.booking.bookingId = :bookingId")
    int updateStatus(@Param("bookingId") UUID bookingId, @Param("status") OtpStatus status, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE BookingOtp o SET o.attempts = o.attempts + 1, o.resendCooldownUntil = :cooldown, o.updatedAt = :now WHERE o.booking.bookingId = :bookingId")
    int incrementAttemptsAndSetCooldown(@Param("bookingId") UUID bookingId, @Param("cooldown") Instant cooldown, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE BookingOtp o SET o.hashedOtp = :hashedOtp, o.updatedAt = :now WHERE o.booking.bookingId = :bookingId")
    int updateHashedOtp(@Param("bookingId") UUID bookingId, @Param("hashedOtp") String hashedOtp, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE BookingOtp o SET o.status = :status, o.expiresAt = :expiresAt, o.updatedAt = :now WHERE o.booking.bookingId = :bookingId")
    int updateStatusAndExpires(@Param("bookingId") UUID bookingId, @Param("status") OtpStatus status, @Param("expiresAt") Instant expiresAt, @Param("now") Instant now);
}
