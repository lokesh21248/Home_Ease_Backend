package com.homeease.backend.repository;

import com.homeease.backend.model.entity.Booking;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.BookingType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID>, JpaSpecificationExecutor<Booking> {
    List<Booking> findByUser_UserIdOrderByCreatedAtDesc(UUID userId);
    List<Booking> findByUser_UserId(UUID userId);
    List<Booking> findByUser_UserIdAndBookingTypeOrderByCreatedAtDesc(UUID userId, BookingType bookingType);
    List<Booking> findByWorker_WorkerId(UUID workerId);
    List<Booking> findByWorker_WorkerIdOrderByCreatedAtDesc(UUID workerId);
    List<Booking> findByWorker_WorkerIdOrderByScheduledAtAsc(UUID workerId);
    List<Booking> findByWorker_WorkerIdAndStatus(UUID workerId, BookingStage status);
    List<Booking> findByWorker_WorkerIdAndStatusInOrderByScheduledAtAsc(UUID workerId, Collection<BookingStage> statuses);
    List<Booking> findByWorker_WorkerIdAndScheduledAtBetweenOrderByScheduledAtAsc(UUID workerId, Instant start, Instant end);
    List<Booking> findByStatus(BookingStage status);
    List<Booking> findByStatusIn(Collection<BookingStage> statuses);

    /**
     * Protection against overlapping worker bookings: counts active bookings for this worker
     * whose time window intersects [start, end].
     */
    @Query("SELECT COUNT(b) FROM Booking b " +
           "WHERE b.worker.workerId = :workerId " +
           "AND b.status IN :activeStatuses " +
           "AND b.scheduledAt < :endTime " +
           "AND (b.scheduledEndAt IS NULL OR b.scheduledEndAt > :startTime)")
    long countOverlappingBookings(
            @Param("workerId") UUID workerId,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime,
            @Param("activeStatuses") Collection<BookingStage> activeStatuses);

    /**
     * P0-1 — Pessimistic write lock for concurrent acceptance protection.
     * Issues SELECT ... FOR UPDATE, serialising concurrent worker acceptance
     * within the enclosing @Transactional boundary.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.bookingId = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") UUID id);

    /**
     * P0-2 — Idempotency check: returns an existing booking that was created
     * with the same client-supplied key, if any.
     */
    Optional<Booking> findByIdempotencyKey(String idempotencyKey);

    /**
     * Phase 2 Assignment Engine: Counts completed jobs since a given timestamp for fairness scoring.
     */
    @Query("SELECT COUNT(b) FROM Booking b " +
           "WHERE b.worker.workerId = :workerId " +
           "  AND b.status = com.homeease.backend.model.enums.BookingStage.COMPLETED " +
           "  AND b.completedAt >= :since")
    int countCompletedJobsSince(@Param("workerId") UUID workerId, @Param("since") Instant since);

    /**
     * Phase 2 T-120 Batch Assigner: Finds unassigned scheduled bookings starting in [windowStart, windowEnd].
     */
    @Query("SELECT b FROM Booking b " +
           "WHERE b.bookingType = com.homeease.backend.model.enums.BookingType.SCHEDULED " +
           "  AND b.worker IS NULL " +
           "  AND b.status IN (com.homeease.backend.model.enums.BookingStage.CONFIRMED, com.homeease.backend.model.enums.BookingStage.REQUESTED, com.homeease.backend.model.enums.BookingStage.SEARCHING) " +
           "  AND b.scheduledAt >= :windowStart " +
           "  AND b.scheduledAt < :windowEnd " +
           "ORDER BY b.scheduledAt ASC")
    List<Booking> findUnassignedScheduledBookingsInWindow(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd);
}
