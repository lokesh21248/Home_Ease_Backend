package com.homeease.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

@Entity
@Table(name = "booking_analytics_daily")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@IdClass(BookingAnalyticsDaily.Key.class)
public class BookingAnalyticsDaily {

    @Id
    @Column(name = "day", nullable = false)
    private LocalDate day;

    @Id
    @Column(name = "service_pincode", nullable = false, length = 6)
    @Builder.Default
    private String servicePincode = "";

    @Column(name = "created_count", nullable = false)
    @Builder.Default
    private Long createdCount = 0L;

    @Column(name = "accepted_count", nullable = false)
    @Builder.Default
    private Long acceptedCount = 0L;

    @Column(name = "started_count", nullable = false)
    @Builder.Default
    private Long startedCount = 0L;

    @Column(name = "completed_count", nullable = false)
    @Builder.Default
    private Long completedCount = 0L;

    @Column(name = "cancelled_count", nullable = false)
    @Builder.Default
    private Long cancelledCount = 0L;

    @Column(name = "payment_success_count", nullable = false)
    @Builder.Default
    private Long paymentSuccessCount = 0L;

    @Column(name = "payment_failed_count", nullable = false)
    @Builder.Default
    private Long paymentFailedCount = 0L;

    @Column(name = "payment_refunded_count", nullable = false)
    @Builder.Default
    private Long paymentRefundedCount = 0L;

    @Column(name = "create_to_accept_ms_sum", nullable = false)
    @Builder.Default
    private Long createToAcceptMsSum = 0L;

    @Column(name = "create_to_accept_n", nullable = false)
    @Builder.Default
    private Long createToAcceptN = 0L;

    @Column(name = "accept_to_start_ms_sum", nullable = false)
    @Builder.Default
    private Long acceptToStartMsSum = 0L;

    @Column(name = "accept_to_start_n", nullable = false)
    @Builder.Default
    private Long acceptToStartN = 0L;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private LocalDate day;
        private String servicePincode;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(day, key.day) && Objects.equals(servicePincode, key.servicePincode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(day, servicePincode);
        }
    }
}
