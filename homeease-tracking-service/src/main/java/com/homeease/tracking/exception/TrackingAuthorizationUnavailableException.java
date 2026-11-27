package com.homeease.tracking.exception;

/**
 * Thrown when the tracking service cannot verify booking assignment/authorization
 * (e.g. Redis timeout or connection failure). Fails closed so unverified requests
 * never broadcast worker coordinates.
 */
public class TrackingAuthorizationUnavailableException extends RuntimeException {
    public TrackingAuthorizationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
