package com.homeease.backend.repository;

import com.homeease.backend.model.entity.BookingSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingSlotRepository extends JpaRepository<BookingSlot, UUID> {

    List<BookingSlot> findByWorker_WorkerIdAndSlotDateOrderByStartTimeAsc(UUID workerId, LocalDate slotDate);

    List<BookingSlot> findBySlotDateAndStatus(LocalDate slotDate, String status);

    @Query("SELECT bs FROM BookingSlot bs " +
           "WHERE bs.worker.workerId = :workerId " +
           "AND bs.slotDate = :slotDate " +
           "AND bs.startTime = :startTime " +
           "AND bs.status IN ('RESERVED', 'CONFIRMED')")
    Optional<BookingSlot> findActiveSlot(
            @Param("workerId") UUID workerId,
            @Param("slotDate") LocalDate slotDate,
            @Param("startTime") LocalTime startTime);

    @Query("SELECT COUNT(bs) FROM BookingSlot bs " +
           "WHERE bs.worker.workerId = :workerId " +
           "AND bs.status IN ('RESERVED', 'CONFIRMED') " +
           "AND bs.startTimestamp < :endTimestamp AND bs.endTimestamp > :startTimestamp")
    long countOverlappingSlots(
            @Param("workerId") UUID workerId,
            @Param("startTimestamp") Instant startTimestamp,
            @Param("endTimestamp") Instant endTimestamp);

    @Query("SELECT COUNT(bs) FROM BookingSlot bs " +
           "WHERE bs.windowKey = :windowKey " +
           "AND bs.status IN ('RESERVED', 'CONFIRMED') " +
           "AND (bs.lockedUntil IS NULL OR bs.lockedUntil > :now)")
    long countActiveHoldsInWindow(
            @Param("windowKey") String windowKey,
            @Param("now") Instant now);

    Optional<BookingSlot> findByHoldTokenAndStatus(String holdToken, String status);

    Optional<BookingSlot> findByLockToken(String lockToken);

    @Query("SELECT bs FROM BookingSlot bs " +
           "WHERE bs.status = 'RESERVED' " +
           "AND bs.lockedUntil < :now")
    List<BookingSlot> findExpiredHolds(@Param("now") Instant now);

    /**
     * Find RESERVED holds in a window that were created before a cutoff time (stale holds).
     * Used to auto-clean holds that accumulated from repeated slot taps in the same session.
     */
    @Query("SELECT bs FROM BookingSlot bs " +
           "WHERE bs.windowKey = :windowKey " +
           "AND bs.status = 'RESERVED' " +
           "AND bs.createdAt < :cutoff")
    List<BookingSlot> findStaleHoldsInWindow(
            @Param("windowKey") String windowKey,
            @Param("cutoff") Instant cutoff);
}

