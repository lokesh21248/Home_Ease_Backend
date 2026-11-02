package com.homeease.backend.repository;

import com.homeease.backend.model.entity.BookingAssignment;
import com.homeease.backend.model.enums.AssignmentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingAssignmentRepository extends JpaRepository<BookingAssignment, UUID> {

    List<BookingAssignment> findByBooking_BookingId(UUID bookingId);

    List<BookingAssignment> findByBooking_BookingIdAndStatus(UUID bookingId, AssignmentStatus status);

    List<BookingAssignment> findByWorker_WorkerIdAndStatus(UUID workerId, AssignmentStatus status);

    List<BookingAssignment> findByWorker_WorkerIdAndStatusIn(UUID workerId, List<AssignmentStatus> statuses);

    List<BookingAssignment> findByStatusInOrderByRequestedAtAsc(List<AssignmentStatus> statuses);

    List<BookingAssignment> findByStatusAndExpiresAtBefore(AssignmentStatus status, Instant now);

    Optional<BookingAssignment> findByBooking_BookingIdAndWorker_WorkerId(UUID bookingId, UUID workerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT a FROM BookingAssignment a
        WHERE a.booking.bookingId = :bookingId
          AND a.worker.workerId = :workerId
          AND a.status = com.homeease.backend.model.enums.AssignmentStatus.PENDING
        """)
    Optional<BookingAssignment> findByBookingIdAndWorkerForUpdate(
            @Param("bookingId") UUID bookingId,
            @Param("workerId") UUID workerId);

    @Modifying
    @Query("""
        UPDATE BookingAssignment a
        SET a.status = :status, a.respondedAt = :now
        WHERE a.booking.bookingId = :bookingId
          AND a.status = com.homeease.backend.model.enums.AssignmentStatus.PENDING
          AND a.worker.workerId != :winningWorkerId
        """)
    int dropOtherPendingAssignments(
            @Param("bookingId") UUID bookingId,
            @Param("winningWorkerId") UUID winningWorkerId,
            @Param("status") AssignmentStatus status,
            @Param("now") Instant now);
}
