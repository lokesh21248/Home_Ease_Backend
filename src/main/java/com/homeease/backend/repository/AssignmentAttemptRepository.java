package com.homeease.backend.repository;

import com.homeease.backend.model.entity.AssignmentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AssignmentAttemptRepository extends JpaRepository<AssignmentAttempt, UUID> {
    List<AssignmentAttempt> findByBooking_BookingId(UUID bookingId);
    Optional<AssignmentAttempt> findByBooking_BookingIdAndWorker_WorkerId(UUID bookingId, UUID workerId);
}
