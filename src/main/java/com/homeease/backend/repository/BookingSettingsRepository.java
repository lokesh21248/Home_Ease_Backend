package com.homeease.backend.repository;

import com.homeease.backend.model.entity.BookingSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingSettingsRepository extends JpaRepository<BookingSettings, UUID> {
    Optional<BookingSettings> findByService_ServiceId(UUID serviceId);
    Optional<BookingSettings> findByServiceIsNull();
}

