package com.homeease.backend.controller;

import com.homeease.backend.dto.BookingDto.BookingResponse;
import com.homeease.backend.service.BookingStateMachine;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping({"/api/v1/billing", "/api/billing"})
public class BillingController {

    private final BookingStateMachine bookingStateMachine;

    public BillingController(BookingStateMachine bookingStateMachine) {
        this.bookingStateMachine = bookingStateMachine;
    }

    @PostMapping("/calculate")
    public ResponseEntity<Map<String, Object>> calculateBilling(@RequestBody Map<String, Object> request) {
        BigDecimal baseAmount = new BigDecimal(request.getOrDefault("baseAmount", "500").toString());
        BigDecimal extraAmount = new BigDecimal(request.getOrDefault("extraAmount", "0").toString());
        BigDecimal discount = new BigDecimal(request.getOrDefault("discount", "0").toString());

        BigDecimal subtotal = baseAmount.add(extraAmount).subtract(discount);
        BigDecimal commission = subtotal.multiply(new BigDecimal("0.02")); // 2% platform commission
        BigDecimal netPayout = subtotal.subtract(commission);

        Map<String, Object> response = new HashMap<>();
        response.put("subtotal", subtotal);
        response.put("commission", commission);
        response.put("netPayout", netPayout);
        response.put("currency", "INR");

        return ResponseEntity.ok(response);
    }

    @GetMapping("/invoice/{bookingId}")
    public ResponseEntity<BookingResponse> getInvoiceForBooking(@PathVariable("bookingId") UUID bookingId) {
        return ResponseEntity.ok(bookingStateMachine.getBookingById(bookingId));
    }
}
