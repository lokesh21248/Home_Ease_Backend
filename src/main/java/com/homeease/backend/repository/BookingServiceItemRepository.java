package com.homeease.backend.repository;

import com.homeease.backend.model.entity.BookingServiceItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BookingServiceItemRepository extends JpaRepository<BookingServiceItem, UUID> {
    List<BookingServiceItem> findByBooking_BookingId(UUID bookingId);

    /**
     * Sub-service IDs of a booking's line items, as plain IDs: needs no open session and loads no lazy entity
     * (used by the dispatch recovery job, which runs outside any transaction).
     */
    @Query("SELECT i.subService.subServiceId FROM BookingServiceItem i WHERE i.booking.bookingId = :bookingId")
    List<UUID> findSubServiceIdsByBookingId(@Param("bookingId") UUID bookingId);
}
