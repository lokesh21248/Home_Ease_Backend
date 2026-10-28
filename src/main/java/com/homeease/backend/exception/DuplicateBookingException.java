package com.homeease.backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicateBookingException extends RuntimeException {

    private final UUID existingBookingId;

    public DuplicateBookingException(UUID existingBookingId) {
        super("Duplicate booking request. Booking already created with ID: " + existingBookingId);
        this.existingBookingId = existingBookingId;
    }

    public UUID getExistingBookingId() {
        return existingBookingId;
    }
}
