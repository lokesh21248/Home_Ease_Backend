package com.homeease.backend.repository;

import com.homeease.backend.model.entity.ServiceAddon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ServiceAddonRepository extends JpaRepository<ServiceAddon, UUID> {
    List<ServiceAddon> findBySubService_SubServiceIdAndIsActiveTrue(UUID subServiceId);

    @Modifying
    @Query("DELETE FROM ServiceAddon sa WHERE sa.subService.subServiceId = :subServiceId")
    void deleteBySubServiceId(@Param("subServiceId") UUID subServiceId);
}

