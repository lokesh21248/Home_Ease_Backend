package com.homeease.backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a pincode is not configured, not active, or belongs to an
 * inactive city or state. Results in HTTP 422 Unprocessable Entity so the
 * client can distinguish serviceability failures from normal 400 validation
 * errors.
 */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class ServiceabilityException extends RuntimeException {

    public ServiceabilityException(String message) {
        super(message);
    }
}
