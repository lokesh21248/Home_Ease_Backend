package com.homeease.backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class JobAlreadyAcceptedException extends RuntimeException {
    public JobAlreadyAcceptedException(String message) {
        super(message);
    }
}

