package com.homeease.backend.repository;

import com.homeease.backend.model.entity.ServiceHowItWorks;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ServiceHowItWorksRepository extends JpaRepository<ServiceHowItWorks, UUID> {
    List<ServiceHowItWorks> findByService_ServiceIdOrderByStepNumberAsc(UUID serviceId);
}

