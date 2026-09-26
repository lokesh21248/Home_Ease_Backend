package com.homeease.backend.repository;

import com.homeease.backend.model.entity.SubService;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SubServiceRepository extends JpaRepository<SubService, UUID> {
    List<SubService> findByService_ServiceIdAndIsActiveTrue(UUID serviceId);
}
