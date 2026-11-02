package com.homeease.backend.repository;

import com.homeease.backend.model.entity.BusinessWorkingHours;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BusinessWorkingHoursRepository extends JpaRepository<BusinessWorkingHours, UUID> {
    List<BusinessWorkingHours> findByServiceIsNullOrderByDayOfWeekAsc();
    List<BusinessWorkingHours> findByService_ServiceIdOrderByDayOfWeekAsc(UUID serviceId);
    Optional<BusinessWorkingHours> findByServiceIsNullAndDayOfWeek(Integer dayOfWeek);
    Optional<BusinessWorkingHours> findByService_ServiceIdAndDayOfWeek(UUID serviceId, Integer dayOfWeek);
}

