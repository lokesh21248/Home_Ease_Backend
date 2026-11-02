package com.homeease.backend.repository;

import com.homeease.backend.model.entity.BookingEvent;
import com.homeease.backend.model.enums.BookingEventType;
import com.homeease.backend.model.enums.EventDeliveryStatus;
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
public interface BookingEventRepository extends JpaRepository<BookingEvent, UUID> {

    List<BookingEvent> findByBooking_BookingId(UUID bookingId);

    List<BookingEvent> findByBooking_BookingIdOrderByCreatedAtAsc(UUID bookingId);

    List<BookingEvent> findByEventType(BookingEventType eventType);

    List<BookingEvent> findByDeliveryStatus(EventDeliveryStatus status);

    List<BookingEvent> findByDeliveryStatusAndCreatedAtBefore(
            EventDeliveryStatus status, Instant now);

    Optional<BookingEvent> findByBooking_BookingIdAndEventType(UUID bookingId, BookingEventType eventType);

    @Modifying
    @Query("UPDATE BookingEvent e SET e.publishedAt = :t, e.consumedBy = :consumer, e.deliveryStatus = :status, e.attemptNo = e.attemptNo + 1, e.errorMessage = :err WHERE e.eventId = :eventId")
    int markPublishedAndConsumed(@Param("eventId") UUID eventId,
                                 @Param("t") Instant t,
                                 @Param("consumer") String consumer,
                                 @Param("status") EventDeliveryStatus status,
                                 @Param("err") String err);

    @Modifying
    @Query("UPDATE BookingEvent e SET e.deliveryStatus = :status, e.errorMessage = :err WHERE e.eventId = :eventId")
    int updateDeliveryStatus(@Param("eventId") UUID eventId, @Param("status") EventDeliveryStatus status, @Param("err") String err);
}
