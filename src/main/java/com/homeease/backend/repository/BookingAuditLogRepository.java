package com.homeease.backend.repository;

import com.homeease.backend.model.entity.BookingAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BookingAuditLogRepository extends JpaRepository<BookingAuditLog, UUID> {
    List<BookingAuditLog> findByBooking_BookingIdOrderByCreatedAtDesc(UUID bookingId);
    Page<BookingAuditLog> findByBooking_BookingIdOrderByCreatedAtDesc(UUID bookingId, Pageable pageable);
    Page<BookingAuditLog> findByEntityTypeOrderByCreatedAtDesc(String entityType, Pageable pageable);
    Page<BookingAuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
}

