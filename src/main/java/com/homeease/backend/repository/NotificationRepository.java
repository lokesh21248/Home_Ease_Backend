package com.homeease.backend.repository;

import com.homeease.backend.model.entity.Notification;
import com.homeease.backend.model.enums.NotifyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    List<Notification> findByStatusAndScheduledAtLessThanEqual(NotifyStatus status, Instant scheduledAt);
    List<Notification> findByUser_UserIdOrderByCreatedAtDesc(UUID userId);
    List<Notification> findByWorker_WorkerIdOrderByCreatedAtDesc(UUID workerId);
}
