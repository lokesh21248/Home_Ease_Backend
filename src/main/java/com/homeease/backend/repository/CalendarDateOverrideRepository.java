package com.homeease.backend.repository;

import com.homeease.backend.model.entity.CalendarDateOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CalendarDateOverrideRepository extends JpaRepository<CalendarDateOverride, UUID> {

    List<CalendarDateOverride> findByOverrideDateBetweenOrderByOverrideDateAsc(LocalDate startDate, LocalDate endDate);

    List<CalendarDateOverride> findByServiceIsNullAndOverrideDateBetweenOrderByOverrideDateAsc(LocalDate startDate, LocalDate endDate);

    List<CalendarDateOverride> findByService_ServiceIdAndOverrideDateBetweenOrderByOverrideDateAsc(UUID serviceId, LocalDate startDate, LocalDate endDate);

    Optional<CalendarDateOverride> findFirstByServiceIsNullAndOverrideDate(LocalDate date);

    Optional<CalendarDateOverride> findFirstByService_ServiceIdAndOverrideDate(UUID serviceId, LocalDate date);

    @Query("SELECT c FROM CalendarDateOverride c WHERE (c.service IS NULL OR c.service.serviceId = :serviceId) AND c.overrideDate = :date ORDER BY c.service.serviceId NULLS LAST")
    List<CalendarDateOverride> findApplicableOverrides(@Param("serviceId") UUID serviceId, @Param("date") LocalDate date);
}

