package com.homeease.backend.repository;

import com.homeease.backend.model.entity.BookingCartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingCartItemRepository extends JpaRepository<BookingCartItem, UUID> {
    List<BookingCartItem> findByCart_CartId(UUID cartId);
    Optional<BookingCartItem> findByCart_CartIdAndCartItemId(UUID cartId, UUID cartItemId);
    Optional<BookingCartItem> findByCart_CartIdAndService_ServiceIdAndDuration_DurationId(
            UUID cartId, UUID serviceId, UUID durationId);
    Optional<BookingCartItem> findByCart_CartIdAndService_ServiceIdAndServicePackage_PackageId(
            UUID cartId, UUID serviceId, UUID packageId);
    Optional<BookingCartItem> findByCart_CartIdAndService_ServiceIdAndSubService_SubServiceId(
            UUID cartId, UUID serviceId, UUID subServiceId);
    void deleteByCart_CartId(UUID cartId);
}

