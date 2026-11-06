package com.homeease.backend.repository;

import com.homeease.backend.model.entity.Dispute;
import com.homeease.backend.model.enums.DisputeStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DisputeRepository extends JpaRepository<Dispute, UUID> {
    Optional<Dispute> findByBooking_BookingId(UUID bookingId);
    List<Dispute> findByStatus(DisputeStatus status);
    Page<Dispute> findAllByOrderByCreatedAtDesc(Pageable pageable);
}

