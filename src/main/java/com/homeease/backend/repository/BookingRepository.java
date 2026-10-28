package com.homeease.backend.repository;

import com.homeease.backend.model.entity.Booking;
import com.homeease.backend.model.enums.BookingStage;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID> {
    List<Booking> findByUser_UserIdOrderByCreatedAtDesc(UUID userId);
    List<Booking> findByWorker_WorkerIdOrderByCreatedAtDesc(UUID workerId);
    List<Booking> findByWorker_WorkerIdAndStatus(UUID workerId, BookingStage status);
    List<Booking> findByStatus(BookingStage status);

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
}
