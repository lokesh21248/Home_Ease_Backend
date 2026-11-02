package com.homeease.backend.repository;

import com.homeease.backend.model.entity.WorkerTimeOff;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface WorkerTimeOffRepository extends JpaRepository<WorkerTimeOff, UUID> {

    List<WorkerTimeOff> findByWorker_WorkerIdOrderByStartDateAsc(UUID workerId);

    @Query("SELECT wto FROM WorkerTimeOff wto " +
           "WHERE wto.worker.workerId = :workerId " +
           "AND wto.startDate <= :date AND wto.endDate >= :date")
    List<WorkerTimeOff> findByWorkerAndDate(
            @Param("workerId") UUID workerId,
            @Param("date") LocalDate date);

    @Query("SELECT wto FROM WorkerTimeOff wto " +
           "WHERE wto.startDate <= :endDate AND wto.endDate >= :startDate")
    List<WorkerTimeOff> findOverlappingTimeOff(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);
}

