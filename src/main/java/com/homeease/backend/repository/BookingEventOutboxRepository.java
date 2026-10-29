package com.homeease.backend.repository;

import com.homeease.backend.model.entity.BookingEventOutbox;
import com.homeease.backend.model.enums.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface BookingEventOutboxRepository extends JpaRepository<BookingEventOutbox, UUID> {

    /**
     * Concurrent-safe outbox poll query. Uses SKIP LOCKED so horizontally-scaled
     * backend instances or test worker threads never process the same rows concurrently.
     */
    @Query(value = "SELECT * FROM booking_event_outbox " +
            "WHERE status = 'PENDING' AND next_attempt_at <= :now " +
            "ORDER BY created_at ASC " +
            "LIMIT :batchSize " +
            "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<BookingEventOutbox> findPendingEventsForProcessing(@Param("now") Instant now,
                                                           @Param("batchSize") int batchSize);

    /**
     * Fallback poll query for in-memory or database dialects (e.g. H2) that may not support native SKIP LOCKED.
     */
    @Query("SELECT b FROM BookingEventOutbox b " +
            "WHERE b.status = :status AND b.nextAttemptAt <= :now " +
            "ORDER BY b.createdAt ASC")
    List<BookingEventOutbox> findTopPending(@Param("status") OutboxStatus status,
                                           @Param("now") Instant now,
                                           org.springframework.data.domain.Pageable pageable);

    @Modifying
    @Query("DELETE FROM BookingEventOutbox b WHERE b.status = :status AND b.publishedAt < :cutoff")
    int deleteByStatusAndPublishedAtBefore(@Param("status") OutboxStatus status,
                                          @Param("cutoff") Instant cutoff);

    long countByStatus(OutboxStatus status);
}
