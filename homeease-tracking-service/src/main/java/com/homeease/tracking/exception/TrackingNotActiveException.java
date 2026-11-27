package com.homeease.tracking.exception;

/**
 * Thrown when a GPS update is sent for a booking whose assignment exists and matches
 * the caller, but the booking is not currently in {@code ON_THE_WAY} active travel
 * (for example {@code ACCEPTED}, {@code CONFIRMED}, {@code SEARCHING}, or paused).
 * Extends {@link IllegalStateException} for caller compatibility while carrying
 * structured state details for HTTP 409 responses.
 */
public class TrackingNotActiveException extends IllegalStateException {

    private final String bookingStatus;

    public TrackingNotActiveException(String bookingStatus, String message) {
        super(message);
        this.bookingStatus = bookingStatus;
    }

    public String getBookingStatus() {
        return bookingStatus;
    }
}
