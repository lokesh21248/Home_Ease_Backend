package com.homeease.backend.repository;

import com.homeease.backend.model.entity.BookingPricingDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingPricingDetailRepository extends JpaRepository<BookingPricingDetail, UUID> {
    Optional<BookingPricingDetail> findByBooking_BookingId(UUID bookingId);
}

