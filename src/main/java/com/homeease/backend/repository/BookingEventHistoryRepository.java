package com.homeease.backend.repository;

import com.homeease.backend.model.entity.BookingEventHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BookingEventHistoryRepository extends JpaRepository<BookingEventHistory, UUID> {

    Page<BookingEventHistory> findByBookingIdOrderByOccurredAtAsc(UUID bookingId, Pageable pageable);

    List<BookingEventHistory> findByBookingIdOrderByOccurredAtAsc(UUID bookingId);

    boolean existsByEventId(UUID eventId);
}
