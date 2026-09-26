package com.homeease.backend.repository;

import com.homeease.backend.model.entity.Worker;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkerRepository extends JpaRepository<Worker, UUID> {

    Optional<Worker> findByUser_UserId(UUID userId);

    @Query(value = """
        SELECT w.* 
        FROM workers w
        JOIN worker_services ws ON w.worker_id = ws.worker_id
        WHERE ws.sub_service_id = :targetSubServiceId
          AND w.is_online = true 
          AND w.is_verified = true
          AND (w.blocked_until IS NULL OR w.blocked_until < CURRENT_TIMESTAMP)
          AND ST_Distance_Sphere(w.location, ST_GeomFromText(CONCAT('POINT(', :userLng, ' ', :userLat, ')'), 4326)) <= :radiusMeters
        ORDER BY ST_Distance_Sphere(w.location, ST_GeomFromText(CONCAT('POINT(', :userLng, ' ', :userLat, ')'), 4326)) ASC
        LIMIT 5
        """, nativeQuery = true)
    List<Worker> findCandidateWorkersNearby(
            @Param("targetSubServiceId") String targetSubServiceId,
            @Param("userLat") Double userLat,
            @Param("userLng") Double userLng,
            @Param("radiusMeters") Double radiusMeters
    );

    List<Worker> findByIsVerifiedFalse();
}
