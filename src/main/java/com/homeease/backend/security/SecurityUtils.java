package com.homeease.backend.security;

import com.homeease.backend.model.enums.UserRole;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

public final class SecurityUtils {

    private SecurityUtils() {}

    /**
     * Resolves the authenticated user's ID strictly from SecurityContext.
     * Prevents IDOR and identity spoofing: only users with ADMIN role may provide an explicit override.
     *
     * @param requestedUserId optional target user ID requested in query
     * @param headerUserIdStr optional target user ID requested in header
     * @return the verified UUID
     */
    public static UUID resolveAuthenticatedUserId(UUID requestedUserId, String headerUserIdStr) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            throw new SecurityException("Unauthorized: Valid JWT authentication token required.");
        }

        // If the caller is an ADMIN, they are authorized to act on behalf of a user for ops/support
        if (principal.getRole() == UserRole.ADMIN) {
            if (headerUserIdStr != null && !headerUserIdStr.isBlank()) {
                try {
                    return UUID.fromString(headerUserIdStr.trim());
                } catch (Exception ignored) {}
            }
            if (requestedUserId != null) {
                return requestedUserId;
            }
        }

        // Standard CUSTOMER or WORKER: strictly return their own authenticated ID!
        return principal.getUserId();
    }
}

