package com.homeease.backend.repository;

import com.homeease.backend.model.entity.AssignmentWeightConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AssignmentWeightConfigRepository extends JpaRepository<AssignmentWeightConfig, UUID> {

    @Query("SELECT c FROM AssignmentWeightConfig c " +
           "WHERE (:serviceId IS NULL OR c.service.serviceId = :serviceId OR c.service IS NULL) " +
           "  AND (:zonePincode IS NULL OR c.zonePincode = :zonePincode OR c.zonePincode IS NULL) " +
           "ORDER BY " +
           "  CASE WHEN c.service.serviceId = :serviceId AND c.zonePincode = :zonePincode THEN 1 " +
           "       WHEN c.service.serviceId = :serviceId THEN 2 " +
           "       WHEN c.zonePincode = :zonePincode THEN 3 " +
           "       ELSE 4 END ASC, " +
           "  c.updatedAt DESC " +
           "LIMIT 1")
    Optional<AssignmentWeightConfig> findBestMatchingConfig(
            @Param("serviceId") UUID serviceId,
            @Param("zonePincode") String zonePincode
    );
}

