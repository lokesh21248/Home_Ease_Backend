package com.homeease.tracking.exception;

/**
 * Thrown when the critical synchronous Redis write of the latest authoritative GPS
 * position fails after bounded retries. No WebSocket/PubSub broadcast or HTTP 200
 * acknowledgment is emitted when persistence fails, so callers retain and retry
 * their latest pending fix.
 */
public class TrackingPersistenceUnavailableException extends RuntimeException {
    public TrackingPersistenceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
