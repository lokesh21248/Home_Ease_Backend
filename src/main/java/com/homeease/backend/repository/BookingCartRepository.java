package com.homeease.backend.repository;

import com.homeease.backend.model.entity.BookingCart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingCartRepository extends JpaRepository<BookingCart, UUID> {
    Optional<BookingCart> findByUser_UserId(UUID userId);
}

