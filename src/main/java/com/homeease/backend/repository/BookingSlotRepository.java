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
}

