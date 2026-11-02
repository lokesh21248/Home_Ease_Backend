package com.homeease.backend.repository;

import com.homeease.backend.model.entity.WorkerAvailability;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkerAvailabilityRepository extends JpaRepository<WorkerAvailability, UUID> {

    List<WorkerAvailability> findByWorker_WorkerIdOrderByDayOfWeekAsc(UUID workerId);

    Optional<WorkerAvailability> findByWorker_WorkerIdAndDayOfWeek(UUID workerId, Integer dayOfWeek);

    @Query("SELECT wa FROM WorkerAvailability wa WHERE wa.dayOfWeek = :dayOfWeek AND wa.isAvailable = true AND wa.worker.isVerified = true")
    List<WorkerAvailability> findActiveByDayOfWeek(@Param("dayOfWeek") Integer dayOfWeek);

    @Query("SELECT wa FROM WorkerAvailability wa " +
           "JOIN WorkerServiceLink wsl ON wsl.worker.workerId = wa.worker.workerId " +
           "WHERE wa.dayOfWeek = :dayOfWeek AND wa.isAvailable = true AND wa.worker.isVerified = true " +
           "AND wsl.subService.subServiceId = :subServiceId")
    List<WorkerAvailability> findActiveByDayOfWeekAndSubService(
            @Param("dayOfWeek") Integer dayOfWeek,
            @Param("subServiceId") UUID subServiceId);

    void deleteByWorker_WorkerId(UUID workerId);
}

