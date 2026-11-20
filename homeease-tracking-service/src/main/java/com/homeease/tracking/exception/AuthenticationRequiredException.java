package com.homeease.tracking.exception;

/**
 * No verified identity was presented. Distinct from a plain {@link SecurityException} (authenticated but not
 * allowed, HTTP 403) so callers get 401 and the apps know to refresh their session instead of giving up.
 */
public class AuthenticationRequiredException extends SecurityException {
    public AuthenticationRequiredException(String message) {
        super(message);
    }
}
