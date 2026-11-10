package com.homeease.backend.security;

import com.homeease.backend.model.enums.UserRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            Map<String, Object> claims = jwtService.validateAndExtractClaims(token);

            if (claims != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                try {
                    Object userIdObj = claims.get("userId") != null ? claims.get("userId") : claims.get("sub");
                    if (userIdObj != null) {
                        UUID userId = UUID.fromString(userIdObj.toString());
                        String roleStr = claims.get("role") != null ? claims.get("role").toString() : "CUSTOMER";
                        UserRole role;
                        try {
                            role = UserRole.valueOf(roleStr);
                        } catch (Exception e) {
                            role = UserRole.CUSTOMER;
                        }

                        String phoneNumber = claims.get("phoneNumber") != null ? claims.get("phoneNumber").toString() : "";
                        String fullName = claims.get("fullName") != null ? claims.get("fullName").toString() : "";
                        String email = claims.get("email") != null ? claims.get("email").toString() : null;

                        SimpleGrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + role.name());

                        UserPrincipal principal = UserPrincipal.builder()
                                .userId(userId)
                                .phoneNumber(phoneNumber)
                                .email(email)
                                .role(role)
                                .authorities(Collections.singletonList(authority))
                                .build();

                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                        SecurityContextHolder.getContext().setAuthentication(authentication);
                        logger.debug("Authenticated user {} with role ROLE_{}", userId, role.name());
                    }
                } catch (Exception e) {
                    logger.warn("Could not set user authentication in SecurityContext: {}", e.getMessage());
                }
            }
        }

        filterChain.doFilter(request, response);
    }
}

