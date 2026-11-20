package com.homeease.backend.exception;

import org.springframework.http.HttpStatus;

/**
 * Structured, documented failure for the booking road-route endpoint
 * {@code GET /api/v1/tracking/{bookingId}/route}.
 *
 * <p>The {@link Code} is the stable machine-readable contract the mobile apps branch on; the message is
 * safe to show to an end user and never contains provider credentials or upstream response bodies.</p>
 */
public class RoutingException extends RuntimeException {

    /** Stable error codes. Each maps to exactly one HTTP status and a retry hint. */
    public enum Code {
        /** No valid signed JWT was presented. */
        JWT_REQUIRED(HttpStatus.UNAUTHORIZED, false),
        /** Caller is neither the booking's customer, its assigned worker, nor an admin. */
        NOT_BOOKING_PARTICIPANT(HttpStatus.FORBIDDEN, false),
        BOOKING_NOT_FOUND(HttpStatus.NOT_FOUND, false),
        /** Booking is not in a travel stage (ACCEPTED / CONFIRMED / ON_THE_WAY). */
        TRACKING_NOT_ACTIVE(HttpStatus.CONFLICT, false),
        WORKER_NOT_ASSIGNED(HttpStatus.CONFLICT, false),
        /** The persisted booking address has no usable coordinates. */
        DESTINATION_UNAVAILABLE(HttpStatus.UNPROCESSABLE_ENTITY, false),
        /** The worker has not published a GPS fix for this booking yet. */
        WORKER_LOCATION_UNAVAILABLE(HttpStatus.CONFLICT, true),
        /** The worker's latest GPS fix is older than the freshness window. */
        WORKER_LOCATION_STALE(HttpStatus.CONFLICT, true),
        /** The provider answered but found no drivable route between the two points. */
        NO_ROUTE_FOUND(HttpStatus.NOT_FOUND, false),
        /** Server routing credential missing, or the provider rejected it (key restriction / API disabled). */
        ROUTING_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, false),
        /** Provider quota or rate limit hit. */
        ROUTING_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, true),
        /** Provider 5xx / malformed response / transport failure. */
        ROUTING_PROVIDER_ERROR(HttpStatus.BAD_GATEWAY, true),
        ROUTING_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, true);

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

    public RoutingException(Code code, String message) {
        this(code, message, null, null);
    }

    public RoutingException(Code code, String message, Integer retryAfterSeconds) {
        this(code, message, retryAfterSeconds, null);
    }

    public RoutingException(Code code, String message, Integer retryAfterSeconds, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Code getCode() {
        return code;
    }

    public boolean isRetryable() {
        return code.retryable();
    }

    /** Seconds the client should wait before retrying, or {@code null} when there is no specific hint. */
    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
