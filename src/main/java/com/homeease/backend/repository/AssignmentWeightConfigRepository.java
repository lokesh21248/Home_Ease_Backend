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

    @Query(value = "SELECT * FROM assignment_weight_config c " +
           "WHERE (CAST(:serviceId AS uuid) IS NULL OR c.service_id = CAST(:serviceId AS uuid) OR c.service_id IS NULL) " +
           "  AND (CAST(:zonePincode AS varchar) IS NULL OR c.zone_pincode = CAST(:zonePincode AS varchar) OR c.zone_pincode IS NULL) " +
           "ORDER BY " +
           "  CASE WHEN c.service_id = CAST(:serviceId AS uuid) AND c.zone_pincode = CAST(:zonePincode AS varchar) THEN 1 " +
           "       WHEN c.service_id = CAST(:serviceId AS uuid) THEN 2 " +
           "       WHEN c.zone_pincode = CAST(:zonePincode AS varchar) THEN 3 " +
           "       ELSE 4 END ASC, " +
           "  c.updated_at DESC " +
           "LIMIT 1", nativeQuery = true)
    Optional<AssignmentWeightConfig> findBestMatchingConfig(
            @Param("serviceId") UUID serviceId,
            @Param("zonePincode") String zonePincode
    );
}

