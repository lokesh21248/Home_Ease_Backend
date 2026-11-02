package com.homeease.backend.repository;

import com.homeease.backend.model.entity.ServiceSession;
import com.homeease.backend.model.enums.ServiceSessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ServiceSessionRepository extends JpaRepository<ServiceSession, UUID> {

    Optional<ServiceSession> findByBooking_BookingId(UUID bookingId);

    List<ServiceSession> findByStatus(ServiceSessionStatus status);

    List<ServiceSession> findByStatusAndWorker_WorkerId(ServiceSessionStatus status, UUID workerId);

    List<ServiceSession> findByStatusAndWorker_WorkerIdOrderByStartedAtDesc(
            ServiceSessionStatus status, UUID workerId);

    @Query("""
        SELECT s FROM ServiceSession s
        WHERE s.booking.bookingId = :bookingId
          AND s.status = com.homeease.backend.model.enums.ServiceSessionStatus.ACTIVE
        """)
    Optional<ServiceSession> findActiveSessionForBooking(@Param("bookingId") UUID bookingId);
}
