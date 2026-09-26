package com.homeease.backend.repository;

import com.homeease.backend.model.entity.BookingAddonItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BookingAddonItemRepository extends JpaRepository<BookingAddonItem, UUID> {
    List<BookingAddonItem> findByBooking_BookingId(UUID bookingId);
}
