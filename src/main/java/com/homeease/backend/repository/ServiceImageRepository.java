package com.homeease.backend.repository;

import com.homeease.backend.model.entity.ServiceImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ServiceImageRepository extends JpaRepository<ServiceImage, UUID> {
    List<ServiceImage> findByService_ServiceIdOrderByDisplayOrderAsc(UUID serviceId);
}

