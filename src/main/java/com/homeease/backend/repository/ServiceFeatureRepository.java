package com.homeease.backend.repository;

import com.homeease.backend.model.entity.ServiceFeature;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ServiceFeatureRepository extends JpaRepository<ServiceFeature, UUID> {
    List<ServiceFeature> findByService_ServiceIdOrderByDisplayOrderAsc(UUID serviceId);
}

