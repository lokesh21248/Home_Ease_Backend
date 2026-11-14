package com.homeease.backend.exception;

/** The worker already has an in-flight instant job and cannot accept another one (HTTP 409). */
public class WorkerBusyException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public WorkerBusyException(String message) {
        super(message);
    }
}
