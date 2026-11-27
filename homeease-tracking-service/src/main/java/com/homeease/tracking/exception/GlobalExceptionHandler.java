package com.homeease.tracking.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class,
            MethodArgumentNotValidException.class
    })
    public ResponseEntity<Map<String, Object>> handleMalformedRequest(Exception ex) {
        logger.warn("Malformed request in tracking service: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(
                "ERROR", "BAD_REQUEST", false, "Invalid or incomplete tracking request payload"
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        logger.warn("Bad request in tracking service: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(
                "ERROR", "BAD_REQUEST", false, ex.getMessage()
        ));
    }

    @ExceptionHandler(AuthenticationRequiredException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthenticated(AuthenticationRequiredException ex) {
        logger.warn("Unauthenticated tracking request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorBody(
                "ERROR", "JWT_REQUIRED", false, ex.getMessage()
        ));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(SecurityException ex) {
        logger.warn("Security error in tracking service: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorBody(
                "ERROR", "FORBIDDEN", false, ex.getMessage()
        ));
    }

    @ExceptionHandler(TrackingNotActiveException.class)
    public ResponseEntity<Map<String, Object>> handleNotActive(TrackingNotActiveException ex) {
        logger.info("Tracking not active in transit: {}", ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "INACTIVE");
        body.put("code", "TRACKING_NOT_ACTIVE");
        body.put("trackingActive", false);
        body.put("bookingStatus", ex.getBookingStatus());
        body.put("retryable", false);
        body.put("timestamp", Instant.now().toString());
        body.put("error", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        logger.info("Tracking closed/inactive state: {}", ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "INACTIVE");
        body.put("code", "TRACKING_CLOSED");
        body.put("trackingActive", false);
        body.put("retryable", false);
        body.put("timestamp", Instant.now().toString());
        body.put("error", ex.getMessage());
        return ResponseEntity.status(HttpStatus.GONE).body(body);
    }

    @ExceptionHandler(TrackingAuthorizationUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleAuthUnavailable(TrackingAuthorizationUnavailableException ex) {
        logger.error("Tracking authorization unavailable (fail-closed): {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorBody(
                "ERROR", "TRACKING_AUTH_UNAVAILABLE", true, ex.getMessage()
        ));
    }

    @ExceptionHandler(TrackingPersistenceUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handlePersistenceUnavailable(TrackingPersistenceUnavailableException ex) {
        logger.error("Tracking Redis persistence unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorBody(
                "ERROR", "TRACKING_PERSISTENCE_UNAVAILABLE", true, ex.getMessage()
        ));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimit(RateLimitExceededException ex) {
        logger.warn("Rate limit exceeded in tracking service: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(errorBody(
                "RATE_LIMITED", "RATE_LIMITED", true, ex.getMessage()
        ));
    }

    /** Unknown paths (e.g. endpoints that were removed) are a 404, not a server fault. */
    @ExceptionHandler({
            org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.servlet.NoHandlerFoundException.class})
    public ResponseEntity<Map<String, Object>> handleNotFound(Exception ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody(
                "ERROR", "NOT_FOUND", false, "No such tracking endpoint"
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        logger.error("Unhandled error in tracking service: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody(
                "ERROR", "INTERNAL_ERROR", true, "An internal error occurred in the live tracking service"
        ));
    }

    private static Map<String, Object> errorBody(String status, String code, boolean retryable, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("code", code);
        body.put("retryable", retryable);
        body.put("timestamp", Instant.now().toString());
        body.put("error", message);
        return body;
    }
}