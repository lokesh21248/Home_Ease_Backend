package com.homeease.backend.repository;

import com.homeease.backend.model.entity.ServiceDuration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ServiceDurationRepository extends JpaRepository<ServiceDuration, UUID> {
    List<ServiceDuration> findByService_ServiceIdAndIsActiveTrueOrderByDisplayOrderAscMinutesAsc(UUID serviceId);
    List<ServiceDuration> findByService_ServiceIdOrderByDisplayOrderAscMinutesAsc(UUID serviceId);
    Optional<ServiceDuration> findByDurationIdAndService_ServiceId(UUID durationId, UUID serviceId);
}

