package com.homeease.backend.repository;

import com.homeease.backend.model.entity.WorkerServiceId;
import com.homeease.backend.model.entity.WorkerServiceLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WorkerServiceLinkRepository extends JpaRepository<WorkerServiceLink, WorkerServiceId> {
    List<WorkerServiceLink> findById_WorkerId(UUID workerId);
    List<WorkerServiceLink> findById_SubServiceId(UUID subServiceId);
    boolean existsById_WorkerIdAndId_SubServiceId(UUID workerId, UUID subServiceId);

    @Query("""
        SELECT COUNT(wsl) > 0 FROM WorkerServiceLink wsl
        WHERE wsl.id.workerId = :workerId
          AND (
              wsl.id.subServiceId = :subServiceId
              OR wsl.subService.service.serviceId = :subServiceId
          )
    """)
    boolean existsByWorkerIdAndSubServiceOrServiceId(@Param("workerId") UUID workerId, @Param("subServiceId") UUID subServiceId);

    @Query("""
        SELECT COUNT(wsl) > 0 FROM WorkerServiceLink wsl
        WHERE wsl.id.workerId = :workerId
          AND (
              wsl.subService.service.serviceId = :serviceId
              OR wsl.id.subServiceId = :serviceId
          )
    """)
    boolean existsByWorkerIdAndServiceId(@Param("workerId") UUID workerId, @Param("serviceId") UUID serviceId);

    @Modifying
    @Query("DELETE FROM WorkerServiceLink wsl WHERE wsl.id.workerId = :workerId")
    void deleteByWorkerId(@Param("workerId") UUID workerId);
}
