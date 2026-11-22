package com.homeease.backend.repository;

import com.homeease.backend.model.entity.BookingLocationHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface BookingLocationHistoryRepository extends JpaRepository<BookingLocationHistory, Long> {

    /** Oldest first, bounded by the pageable so a long journey can never produce an unbounded response. */
    @Query("SELECT h FROM BookingLocationHistory h WHERE h.bookingId = :bookingId ORDER BY h.recordedAt ASC, h.id ASC")
    List<BookingLocationHistory> findForBooking(@Param("bookingId") UUID bookingId, Pageable pageable);

    long countByBookingId(UUID bookingId);

    @Modifying
    @Query("DELETE FROM BookingLocationHistory h WHERE h.receivedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
