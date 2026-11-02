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
