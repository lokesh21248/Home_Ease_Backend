package com.homeease.backend.repository;

import com.homeease.backend.model.entity.Booking;
import com.homeease.backend.model.enums.BookingStage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID> {
    List<Booking> findByUser_UserIdOrderByCreatedAtDesc(UUID userId);
    List<Booking> findByWorker_WorkerIdOrderByCreatedAtDesc(UUID workerId);
    List<Booking> findByWorker_WorkerIdAndStatus(UUID workerId, BookingStage status);
    List<Booking> findByStatus(BookingStage status);
}
