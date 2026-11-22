package com.homeease.backend.exception;

import org.springframework.http.HttpStatus;

/**
 * Structured failure for the live-tracking API ({@code /api/{v1/}tracking/**}) and the arrival / PIN steps.
 * Same wire shape as {@link RoutingException}: a stable machine-readable {@code code}, a user-safe message,
 * a retry hint. Never carries coordinates, PINs or credentials.
 */
public class TrackingApiException extends RuntimeException {

    public enum Code {
        /** No valid, cryptographically signed JWT. A bare user id is deliberately not accepted. */
        JWT_REQUIRED(HttpStatus.UNAUTHORIZED, false),
        /** Caller is not this booking's customer / assigned partner (or admin). */
        NOT_BOOKING_PARTICIPANT(HttpStatus.FORBIDDEN, false),
        /** Caller is a participant but not allowed this specific action (e.g. customer posting GPS). */
        ROLE_NOT_ALLOWED(HttpStatus.FORBIDDEN, false),
        BOOKING_NOT_FOUND(HttpStatus.NOT_FOUND, false),
        /** The booking is not in a stage where this action makes sense. */
        INVALID_BOOKING_STATE(HttpStatus.CONFLICT, false),
        /** The partner is not close enough to the service address to mark arrival. */
        NOT_AT_DESTINATION(HttpStatus.CONFLICT, false),
        /** Arrival needs a recent GPS fix and there is none. */
        LOCATION_REQUIRED(HttpStatus.CONFLICT, true),
        /** A submitted GPS fix failed validation (range, accuracy, timestamp...). */
        INVALID_GPS_FIX(HttpStatus.UNPROCESSABLE_ENTITY, false),
        /** The same partner is posting faster than allowed. */
        RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, true),
        /** Too many wrong PINs: verification is locked for a while. */
        PIN_LOCKED(HttpStatus.TOO_MANY_REQUESTS, true),
        /** PIN requested before the partner arrived. */
        PIN_NOT_AVAILABLE(HttpStatus.CONFLICT, false);

        private final HttpStatus status;
        private final boolean retryable;

        Code(HttpStatus status, boolean retryable) {
            this.status = status;
            this.retryable = retryable;
        }

        public HttpStatus status() {
            return status;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private final Code code;
    private final Integer retryAfterSeconds;

    public TrackingApiException(Code code, String message) {
        this(code, message, null);
    }

    public TrackingApiException(Code code, String message, Integer retryAfterSeconds) {
        super(message);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Code getCode() {
        return code;
    }

    public boolean isRetryable() {
        return code.retryable();
    }

    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
