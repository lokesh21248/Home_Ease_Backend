package com.homeease.backend.controller;

import com.homeease.backend.dto.BookingDto.*;
import com.homeease.backend.service.BookingStateMachine;
import com.homeease.backend.service.DispatchEngine;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping({"/api/v1", "/api"})
public class BookingController {

    private final BookingStateMachine bookingStateMachine;
    private final DispatchEngine dispatchEngine;

    public BookingController(BookingStateMachine bookingStateMachine, DispatchEngine dispatchEngine) {
        this.bookingStateMachine = bookingStateMachine;
        this.dispatchEngine = dispatchEngine;
    }

    // Customer Endpoints
    @PostMapping("/bookings")
    public ResponseEntity<BookingResponse> createBooking(
            @RequestHeader("X-User-Id") UUID userId,
            @Valid @RequestBody CreateBookingRequest request) {
        return ResponseEntity.ok(bookingStateMachine.createBooking(userId, request));
    }

    @GetMapping("/bookings/my-bookings")
    public ResponseEntity<List<BookingResponse>> getMyBookings(@RequestHeader("X-User-Id") UUID userId) {
        return ResponseEntity.ok(bookingStateMachine.getUserBookings(userId));
    }

    @GetMapping({"/bookings/time-slots", "/slots", "/booking/slots"})
    public ResponseEntity<List<java.util.Map<String, Object>>> getAvailableTimeSlots(
            @RequestParam(value = "date", required = false) String date) {
        String effectiveDate = (date != null && !date.isBlank()) ? date : java.time.LocalDate.now().toString();
        List<String> slotTimes = List.of(
                "09:00 AM - 10:00 AM", "10:00 AM - 11:00 AM", "11:00 AM - 12:00 PM",
                "12:00 PM - 01:00 PM", "02:00 PM - 03:00 PM", "03:00 PM - 04:00 PM",
                "04:00 PM - 05:00 PM", "05:00 PM - 06:00 PM", "06:00 PM - 07:00 PM"
        );

        List<java.util.Map<String, Object>> response = slotTimes.stream()
                .map(time -> java.util.Map.<String, Object>of(
                        "time", time,
                        "available", true,
                        "date", effectiveDate
                ))
                .toList();

        return ResponseEntity.ok(response);
    }

    @GetMapping("/bookings/{id}")
    public ResponseEntity<BookingResponse> getBookingById(@PathVariable("id") UUID bookingId) {
        return ResponseEntity.ok(bookingStateMachine.getBookingById(bookingId));
    }

    @PatchMapping("/bookings/{id}/status")
    public ResponseEntity<BookingResponse> updateBookingStatus(
            @PathVariable("id") UUID bookingId,
            @RequestParam("status") String status,
            @RequestHeader("X-User-Id") UUID userId) {
        if ("ACCEPTED".equalsIgnoreCase(status)) {
            var booking = dispatchEngine.handleWorkerAcceptance(bookingId, userId);
            return ResponseEntity.ok(bookingStateMachine.mapToBookingResponse(booking));
        } else if ("COMPLETED".equalsIgnoreCase(status)) {
            return ResponseEntity.ok(bookingStateMachine.completeJob(bookingId, userId));
        }
        return ResponseEntity.ok(bookingStateMachine.getBookingById(bookingId));
    }

    // Worker Job Execution Endpoints
    @PostMapping("/workers/bookings/{id}/accept")
    public ResponseEntity<BookingResponse> acceptBooking(
            @PathVariable("id") UUID bookingId,
            @RequestHeader("X-User-Id") UUID userId) {
        var booking = dispatchEngine.handleWorkerAcceptance(bookingId, userId);
        return ResponseEntity.ok(bookingStateMachine.mapToBookingResponse(booking));
    }

    @PostMapping("/workers/bookings/{id}/reject")
    public ResponseEntity<String> rejectBooking(
            @PathVariable("id") UUID bookingId,
            @RequestParam(value = "targetSubServiceId", required = false) UUID targetSubServiceId,
            @RequestHeader("X-User-Id") UUID userId) {
        dispatchEngine.handleWorkerRejection(bookingId, userId, targetSubServiceId);
        return ResponseEntity.ok("Booking request rejected. Account temporarily blocked for 1 hour.");
    }

    @PostMapping("/workers/bookings/{id}/verify-pin")
    public ResponseEntity<BookingResponse> verifyPinAndStartJob(
            @PathVariable("id") UUID bookingId,
            @RequestHeader("X-User-Id") UUID userId,
            @Valid @RequestBody PinVerifyRequest request) {
        return ResponseEntity.ok(bookingStateMachine.verifyPinAndStartJob(bookingId, userId, request.getPinCode()));
    }

    @PostMapping({"/workers/bookings/{id}/add-sub-service", "/bookings/{id}/line-items"})
    public ResponseEntity<BookingResponse> addExtraSubService(
            @PathVariable("id") UUID bookingId,
            @RequestHeader("X-User-Id") UUID userId,
            @Valid @RequestBody AddExtraSubServiceRequest request) {
        return ResponseEntity.ok(bookingStateMachine.addExtraSubService(bookingId, userId, request));
    }

    @PostMapping("/workers/bookings/{id}/complete")
    public ResponseEntity<BookingResponse> completeJob(
            @PathVariable("id") UUID bookingId,
            @RequestHeader("X-User-Id") UUID userId) {
        return ResponseEntity.ok(bookingStateMachine.completeJob(bookingId, userId));
    }

    @GetMapping({"/workers/bookings/requests", "/workers/requests"})
    public ResponseEntity<List<com.homeease.backend.dto.WorkerDto.WorkerBookingRequestResponse>> getWorkerBookingRequests(
            @RequestHeader("X-User-Id") UUID userId) {
        return ResponseEntity.ok(dispatchEngine.getPendingRequestsForWorker(userId));
    }
}
