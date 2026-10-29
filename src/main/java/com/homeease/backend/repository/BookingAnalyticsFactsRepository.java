package com.homeease.backend.repository;

import com.homeease.backend.model.entity.BookingAnalyticsFacts;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface BookingAnalyticsFactsRepository extends JpaRepository<BookingAnalyticsFacts, UUID> {
}
