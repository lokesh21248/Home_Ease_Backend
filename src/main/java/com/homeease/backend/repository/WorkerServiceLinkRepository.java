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

    @Modifying
    @Query("DELETE FROM WorkerServiceLink wsl WHERE wsl.id.workerId = :workerId")
    void deleteByWorkerId(@Param("workerId") UUID workerId);
}
