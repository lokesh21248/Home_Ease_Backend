package com.homeease.backend.repository;

import com.homeease.backend.model.entity.WorkerPayoutBatch;
import com.homeease.backend.model.enums.PayoutStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkerPayoutBatchRepository extends JpaRepository<WorkerPayoutBatch, UUID> {
    Optional<WorkerPayoutBatch> findByIdempotencyKey(String idempotencyKey);
    List<WorkerPayoutBatch> findByWorker_WorkerIdOrderByCreatedAtDesc(UUID workerId);
    Page<WorkerPayoutBatch> findByStatusOrderByCreatedAtDesc(PayoutStatus status, Pageable pageable);
}

