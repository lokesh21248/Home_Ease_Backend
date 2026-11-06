package com.homeease.backend.repository;

import com.homeease.backend.model.entity.SlotWindowCapacity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SlotWindowCapacityRepository extends JpaRepository<SlotWindowCapacity, UUID> {

    @Query("SELECT swc FROM SlotWindowCapacity swc " +
           "WHERE swc.service.serviceId = :serviceId " +
           "AND swc.zonePincode = :pincode " +
           "AND swc.dayOfWeek = :dayOfWeek " +
           "AND swc.active = true " +
           "ORDER BY swc.startTime ASC")
    List<SlotWindowCapacity> findActiveWindows(
            @Param("serviceId") UUID serviceId,
            @Param("pincode") String pincode,
            @Param("dayOfWeek") int dayOfWeek);

    @Query("SELECT swc FROM SlotWindowCapacity swc " +
           "WHERE swc.service.serviceId = :serviceId " +
           "AND swc.zonePincode = :pincode " +
           "AND swc.dayOfWeek = :dayOfWeek " +
           "AND swc.startTime = :startTime " +
           "AND swc.active = true")
    Optional<SlotWindowCapacity> findSpecificWindow(
            @Param("serviceId") UUID serviceId,
            @Param("pincode") String pincode,
            @Param("dayOfWeek") int dayOfWeek,
            @Param("startTime") LocalTime startTime);
}

