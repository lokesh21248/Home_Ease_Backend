package com.homeease.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Cost and abuse protection for the PUBLIC (unauthenticated) nearby-workers preview.
 *
 * <p>Each call runs the full worker-discovery path: two Redis GEO queries, one heartbeat check per candidate, and
 * several database queries (about 12 Redis commands per call). With the Redis plan priced per command, and no
 * authentication, anyone could spend the quota or load the database by looping on it.
 *
 * <p>Two limits, both configurable, 0 = off:
 * <ul>
 *   <li><b>per client</b> (default 60 requests/minute): keyed by {@code CF-Connecting-IP}, else {@code X-Real-IP},
 *       else the socket address. These headers can be forged by a caller that reaches the origin directly, which is
 *       why there is also:</li>
 *   <li><b>global</b> (default 1200 requests/minute across all callers): independent of any header, so it bounds the
 *       worst case whatever identity a caller claims.</li>
 * </ul>
 * Over the limit the response is HTTP 429 with {@code Retry-After}; the success response is unchanged. Per JVM only.
 */
@Component
public class NearbyWorkersRateLimitFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(NearbyWorkersRateLimitFilter.class);
    private static final String GLOBAL_KEY = "*";

    private final FixedWindowRateLimiter perClient;
    private final FixedWindowRateLimiter global;

    public NearbyWorkersRateLimitFilter(
            @Value("${homeease.public-api.nearby-workers.per-client-per-minute:60}") int perClientPerMinute,
            @Value("${homeease.public-api.nearby-workers.global-per-minute:1200}") int globalPerMinute) {
        this.perClient = new FixedWindowRateLimiter(perClientPerMinute, 60_000L);
        this.global = new FixedWindowRateLimiter(globalPerMinute, 60_000L);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return !(path.equals("/api/v1/bookings/nearby-workers") || path.equals("/api/bookings/nearby-workers")
                || path.equals("/api/v1/bookings/nearby-workers/") || path.equals("/api/bookings/nearby-workers/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        FixedWindowRateLimiter.Decision byClient = perClient.tryAcquire(clientKey(request));
        FixedWindowRateLimiter.Decision overall = byClient.allowed() ? global.tryAcquire(GLOBAL_KEY) : byClient;
        if (byClient.allowed() && overall.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        long retryAfter = byClient.allowed() ? overall.retryAfterSeconds() : byClient.retryAfterSeconds();
        logger.warn("nearby-workers rate limit exceeded ({} limit)", byClient.allowed() ? "global" : "per-client");
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"timestamp\":\"" + Instant.now() + "\",\"status\":429,\"error\":\"TOO_MANY_REQUESTS\","
                + "\"message\":\"Too many requests. Please retry shortly.\"}");
    }

    static String clientKey(HttpServletRequest request) {
        String cf = request.getHeader("CF-Connecting-IP");
        if (cf != null && !cf.isBlank()) {
            return cf.trim();
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }
}
