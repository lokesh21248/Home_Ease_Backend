package com.homeease.backend.repository;

import com.homeease.backend.model.entity.BookingAnalyticsInbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface BookingAnalyticsInboxRepository extends JpaRepository<BookingAnalyticsInbox, UUID> {
}
