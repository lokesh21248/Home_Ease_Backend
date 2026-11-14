package com.homeease.backend.controller;

import com.homeease.backend.model.enums.UserRole;
import com.homeease.backend.security.UserPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Authenticates a MockMvc request as a real {@link UserPrincipal}, which is what the controllers now require
 * ({@code SecurityUtils.resolveAuthenticatedUserId} ignores the X-User-Id header for non-admins on purpose, to
 * prevent IDOR / identity spoofing). These tests run with {@code addFilters = false}, so the security filter
 * chain never populates the SecurityContext; MockMvc executes on the calling thread, so the thread-local context
 * is set here and must be cleared by the test ({@link #clear()}).
 */
final class TestAuth {

    private TestAuth() { }

    static RequestPostProcessor as(UUID userId, UserRole role) {
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
        UserPrincipal principal = UserPrincipal.builder()
                .userId(userId).role(role).phoneNumber("+910000000000").email("test@example.invalid")
                .authorities(authorities).build();
        return request -> {
            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
            SecurityContextHolder.setContext(ctx);
            return request;
        };
    }

    static RequestPostProcessor customer(UUID userId) { return as(userId, UserRole.CUSTOMER); }

    static RequestPostProcessor worker(UUID userId) { return as(userId, UserRole.WORKER); }

    static void clear() { SecurityContextHolder.clearContext(); }
}
