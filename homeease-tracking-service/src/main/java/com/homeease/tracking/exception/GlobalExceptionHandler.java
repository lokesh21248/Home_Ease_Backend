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
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "status", "ERROR",
                "code", "BAD_REQUEST",
                "timestamp", Instant.now().toString(),
                "error", "Invalid or incomplete tracking request payload"
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        logger.warn("Bad request in tracking service: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "status", "ERROR",
                "timestamp", Instant.now().toString(),
                "error", ex.getMessage()
        ));
    }

    @ExceptionHandler(AuthenticationRequiredException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthenticated(AuthenticationRequiredException ex) {
        logger.warn("Unauthenticated tracking request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "status", "ERROR",
                "code", "JWT_REQUIRED",
                "timestamp", Instant.now().toString(),
                "error", ex.getMessage()
        ));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(SecurityException ex) {
        logger.warn("Security error in tracking service: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "status", "ERROR",
                "timestamp", Instant.now().toString(),
                "error", ex.getMessage()
        ));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        logger.info("Tracking closed/inactive state: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                "status", "INACTIVE",
                "trackingActive", false,
                "timestamp", Instant.now().toString(),
                "error", ex.getMessage()
        ));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimit(RateLimitExceededException ex) {
        logger.warn("Rate limit exceeded in tracking service: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                "status", "RATE_LIMITED",
                "timestamp", Instant.now().toString(),
                "error", ex.getMessage()
        ));
    }

    /** Unknown paths (e.g. endpoints that were removed) are a 404, not a server fault. */
    @ExceptionHandler({
            org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.servlet.NoHandlerFoundException.class})
    public ResponseEntity<Map<String, Object>> handleNotFound(Exception ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "status", "ERROR",
                "code", "NOT_FOUND",
                "timestamp", Instant.now().toString(),
                "error", "No such tracking endpoint"
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        logger.error("Unhandled error in tracking service: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "status", "ERROR",
                "timestamp", Instant.now().toString(),
                "error", "An internal error occurred in the live tracking service"
        ));
    }
}
