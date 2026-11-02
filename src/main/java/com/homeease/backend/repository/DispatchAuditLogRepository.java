package com.homeease.backend.repository;

import com.homeease.backend.model.entity.DispatchAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DispatchAuditLogRepository extends JpaRepository<DispatchAuditLog, UUID> {
    List<DispatchAuditLog> findByBooking_BookingIdOrderByCreatedAtDesc(UUID bookingId);
    Page<DispatchAuditLog> findByBooking_BookingIdOrderByCreatedAtDesc(UUID bookingId, Pageable pageable);
    Page<DispatchAuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
}

