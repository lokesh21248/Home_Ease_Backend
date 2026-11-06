package com.homeease.backend.repository;

import com.homeease.backend.model.entity.ServiceInstruction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ServiceInstructionRepository extends JpaRepository<ServiceInstruction, UUID> {
    List<ServiceInstruction> findByService_ServiceIdOrderByDisplayOrderAsc(UUID serviceId);
}

