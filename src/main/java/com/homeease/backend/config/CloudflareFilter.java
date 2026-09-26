package com.homeease.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class CloudflareFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        String cfConnectingIp = request.getHeader("CF-Connecting-IP");
        String cfVisitor = request.getHeader("CF-Visitor");

        if (cfConnectingIp != null && !cfConnectingIp.isBlank()) {
            request.setAttribute("REMOTE_ADDR_ORIGINAL", request.getRemoteAddr());
            request.setAttribute("CLIENT_IP", cfConnectingIp);
        }

        // Pass-through for Cloudflare Edge proxy
        filterChain.doFilter(request, response);
    }
}
