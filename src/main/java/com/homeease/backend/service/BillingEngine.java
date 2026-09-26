package com.homeease.backend.service;

import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.TransactionState;
import com.homeease.backend.repository.PaymentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class BillingEngine {

    private final PaymentRepository paymentRepository;

    @Value("${homeease.billing.platform-commission-percent:2.00}")
    private BigDecimal platformCommissionPercent;

    public BillingEngine(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Transactional
    public Payment calculateAndPersistSettlement(Booking booking) {
        BigDecimal total = booking.getTotalAmount();
        BigDecimal commissionAmount = total
                .multiply(platformCommissionPercent)
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);

        BigDecimal workerPayout = total.subtract(commissionAmount);

        Payment payment = paymentRepository.findByBooking_BookingId(booking.getBookingId())
                .orElseGet(() -> Payment.builder()
                        .booking(booking)
                        .build());

        payment.setGrossAmount(total);
        payment.setPlatformCommissionPercent(platformCommissionPercent);
        payment.setPlatformCommissionAmount(commissionAmount);
        payment.setWorkerPayoutAmount(workerPayout);
        payment.setStatus(TransactionState.SUCCESS);

        return paymentRepository.save(payment);
    }
}
