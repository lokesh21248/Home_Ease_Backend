package com.homeease.backend.repository;

import com.homeease.backend.model.entity.ReassignmentTriggerLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReassignmentTriggerLogRepository extends JpaRepository<ReassignmentTriggerLog, UUID> {
    List<ReassignmentTriggerLog> findByBooking_BookingIdOrderByCreatedAtDesc(UUID bookingId);
}

