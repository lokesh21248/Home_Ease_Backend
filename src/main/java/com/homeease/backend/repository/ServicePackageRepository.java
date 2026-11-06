package com.homeease.backend.repository;

import com.homeease.backend.model.entity.ServicePackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ServicePackageRepository extends JpaRepository<ServicePackage, UUID> {
    List<ServicePackage> findByService_ServiceIdAndIsActiveTrueOrderByDisplayOrderAsc(UUID serviceId);
    List<ServicePackage> findByService_ServiceIdOrderByDisplayOrderAsc(UUID serviceId);
    Optional<ServicePackage> findByPackageIdAndService_ServiceId(UUID packageId, UUID serviceId);
}

