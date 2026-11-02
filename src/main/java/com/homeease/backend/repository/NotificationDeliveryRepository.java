package com.homeease.backend.repository;

import com.homeease.backend.model.entity.NotificationDelivery;
import com.homeease.backend.model.enums.DeliveryChannel;
import com.homeease.backend.model.enums.DeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, UUID> {

    List<NotificationDelivery> findByBooking_BookingId(UUID bookingId);

    List<NotificationDelivery> findByBooking_BookingIdAndChannel(UUID bookingId, DeliveryChannel channel);

    List<NotificationDelivery> findByBooking_BookingIdAndStatus(UUID bookingId, DeliveryStatus status);

    List<NotificationDelivery> findByStatusInOrderByCreatedAtAsc(List<DeliveryStatus> statuses);

    @Modifying
    @Query("UPDATE NotificationDelivery n SET n.status = :status, n.errorMessage = :err, n.attemptNo = n.attemptNo + 1 WHERE n.deliveryId = :deliveryId")
    int updateStatus(@Param("deliveryId") UUID deliveryId,
                     @Param("status") DeliveryStatus status,
                     @Param("err") String err);

    @Modifying
    @Query("UPDATE NotificationDelivery n SET n.deliveredAt = :t, n.status = :status WHERE n.deliveryId = :deliveryId")
    int markDelivered(@Param("deliveryId") UUID deliveryId, @Param("t") Instant t, @Param("status") DeliveryStatus status);
}
