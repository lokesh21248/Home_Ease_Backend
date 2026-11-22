package com.homeease.backend.security;

import com.homeease.backend.exception.TrackingApiException;
import com.homeease.backend.model.enums.UserRole;

import java.util.Map;
import java.util.UUID;

/**
 * The caller identity taken ONLY from a cryptographically verified JWT.
 *
 * <p>{@code JwtAuthenticationFilter} still honours a legacy "Bearer &lt;uuid&gt;" / {@code X-User-Id} fallback, which means
 * anyone who knows a user's UUID can act as that user on any endpoint that reads the Spring SecurityContext. Endpoints
 * that expose a customer's live location, the doorstep PIN or the arrival/start-service transitions must not rely on
 * that, so they resolve the caller here instead: no valid signature, no access.</p>
 */
public record VerifiedCaller(UUID userId, UserRole role) {

    /**
     * Emergency rollback switch, set from {@code homeease.security.strict-jwt-tracking} (env STRICT_JWT_TRACKING) by
     * {@link StrictJwtSettings}. Default true. When false, a legacy (unverified) principal is accepted again on the
     * tracking / arrival / PIN endpoints. Only for an incident where a client cannot yet send a signed token.
     */
    private static volatile boolean strict = true;

    static void setStrict(boolean value) {
        strict = value;
    }

    public static boolean isStrict() {
        return strict;
    }

    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }

    public boolean isWorker() {
        return role == UserRole.WORKER;
    }

    /**
     * The caller authenticated for this request by {@code JwtAuthenticationFilter}, accepted only if that
     * authentication came from a verified JWT signature (not the bare-UUID fallback).
     *
     * @throws TrackingApiException {@code JWT_REQUIRED}
     */
    public static VerifiedCaller current() {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal) || principal.getUserId() == null) {
            throw new TrackingApiException(TrackingApiException.Code.JWT_REQUIRED, "A valid sign-in is required.");
        }
        if (strict && !principal.isJwtVerified()) {
            throw new TrackingApiException(TrackingApiException.Code.JWT_REQUIRED,
                    "A signed sign-in token is required for this action. Please sign in again.");
        }
        return new VerifiedCaller(principal.getUserId(),
                principal.getRole() == null ? UserRole.CUSTOMER : principal.getRole());
    }

    /**
     * @param authorizationHeader the raw {@code Authorization} header ({@code Bearer <jwt>})
     * @throws TrackingApiException {@code JWT_REQUIRED} when absent, malformed, expired or badly signed
     */
    public static VerifiedCaller require(JwtService jwtService, String authorizationHeader) {
        Map<String, Object> claims = jwtService.validateAndExtractClaims(authorizationHeader);
        if (claims == null) {
            throw new TrackingApiException(TrackingApiException.Code.JWT_REQUIRED, "A valid sign-in is required.");
        }
        Object raw = claims.get("userId") != null ? claims.get("userId") : claims.get("sub");
        UUID id;
        try {
            id = raw == null ? null : UUID.fromString(raw.toString());
        } catch (IllegalArgumentException e) {
            id = null;
        }
        if (id == null) {
            throw new TrackingApiException(TrackingApiException.Code.JWT_REQUIRED, "A valid sign-in is required.");
        }
        UserRole role;
        try {
            role = claims.get("role") == null ? UserRole.CUSTOMER : UserRole.valueOf(claims.get("role").toString());
        } catch (IllegalArgumentException e) {
            role = UserRole.CUSTOMER;
        }
        return new VerifiedCaller(id, role);
    }
}
