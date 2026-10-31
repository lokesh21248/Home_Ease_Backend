package com.homeease.backend.repository;

import com.homeease.backend.model.entity.ServiceablePincode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ServiceablePincodeRepository extends JpaRepository<ServiceablePincode, UUID> {

    Optional<ServiceablePincode> findByPincode(String pincode);

    List<ServiceablePincode> findAllByPincode(String pincode);

    List<ServiceablePincode> findAllByPincodeAndIsActiveTrue(String pincode);

    Optional<ServiceablePincode> findFirstByPincode(String pincode);

    boolean existsByPincodeAndIsActiveTrue(String pincode);

    long countByIsActiveTrue();

    long countByIsActiveFalse();

    long countByCity_CityId(UUID cityId);

    long countByState_StateId(UUID stateId);

    /**
     * Flexible paginated search with optional stateId / cityId / isActive filters.
     * Uses LEFT JOINs so records without optional city/state metadata are not dropped.
     * Uses CAST(:q AS string) to prevent the PostgreSQL function lower(bytea) error.
     * Orders by pincode and areaName without requiring joins to states or cities.
     */
    @Query("""
            SELECT p FROM ServiceablePincode p
            LEFT JOIN p.city c
            LEFT JOIN p.state s
            WHERE (:stateId IS NULL OR s.stateId = :stateId)
              AND (:cityId  IS NULL OR c.cityId   = :cityId)
              AND (:active  IS NULL OR p.isActive       = :active)
              AND (CAST(:q AS string) IS NULL OR CAST(:q AS string) = ''
                   OR LOWER(p.pincode) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                   OR LOWER(p.areaName) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
            ORDER BY p.pincode ASC, p.areaName ASC
            """)
    Page<ServiceablePincode> search(
            @Param("stateId") UUID stateId,
            @Param("cityId")  UUID cityId,
            @Param("active")  Boolean active,
            @Param("q")       String q,
            Pageable pageable);

    /**
     * Fast paginated search without any joins to cities or states when geographic
     * filtering is not requested.
     */
    @Query("""
            SELECT p FROM ServiceablePincode p
            WHERE (:active IS NULL OR p.isActive = :active)
              AND (CAST(:q AS string) IS NULL OR CAST(:q AS string) = ''
                   OR LOWER(p.pincode) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                   OR LOWER(p.areaName) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
            ORDER BY p.pincode ASC, p.areaName ASC
            """)
    Page<ServiceablePincode> searchWithoutGeo(
            @Param("active") Boolean active,
            @Param("q")      String q,
            Pageable pageable);
}
