package com.homeease.backend.repository;

import com.homeease.backend.model.entity.BookingAnalyticsDaily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface BookingAnalyticsDailyRepository extends JpaRepository<BookingAnalyticsDaily, BookingAnalyticsDaily.Key> {

    List<BookingAnalyticsDaily> findByDayBetweenOrderByDayAsc(LocalDate startDay, LocalDate endDay);

    List<BookingAnalyticsDaily> findByDayOrderByServicePincodeAsc(LocalDate day);
}
