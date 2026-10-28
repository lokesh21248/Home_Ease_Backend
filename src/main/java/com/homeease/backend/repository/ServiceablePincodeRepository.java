package com.homeease.backend.repository;

import com.homeease.backend.model.entity.ServiceablePincode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ServiceablePincodeRepository extends JpaRepository<ServiceablePincode, UUID> {

    Optional<ServiceablePincode> findByPincode(String pincode);

    long countByIsActiveTrue();

    long countByIsActiveFalse();

    long countByCity_CityId(UUID cityId);

    long countByState_StateId(UUID stateId);

    /**
     * Flexible paginated search with optional stateId / cityId / isActive filters.
     * All params are nullable — null means "any".
     */
    @Query("""
            SELECT p FROM ServiceablePincode p
            WHERE (:stateId IS NULL OR p.state.stateId = :stateId)
              AND (:cityId  IS NULL OR p.city.cityId   = :cityId)
              AND (:active  IS NULL OR p.isActive       = :active)
              AND (:q       IS NULL OR LOWER(p.pincode) LIKE LOWER(CONCAT('%', :q, '%'))
                                    OR LOWER(p.areaName) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY p.state.name ASC, p.city.name ASC, p.pincode ASC
            """)
    Page<ServiceablePincode> search(
            @Param("stateId") UUID stateId,
            @Param("cityId")  UUID cityId,
            @Param("active")  Boolean active,
            @Param("q")       String q,
            Pageable pageable);
}
