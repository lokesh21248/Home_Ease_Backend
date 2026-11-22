package com.homeease.tracking.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.tracking.exception.AuthenticationRequiredException;
import com.homeease.tracking.exception.RateLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Authorization for the customer-facing read side of live tracking (REST reads and STOMP subscriptions), plus
 * cost protection for the coordinate-based route endpoints.
 *
 * <h3>Rules</h3>
 * <ul>
 *   <li>Reading a booking's worker location requires a <b>cryptographically verified JWT</b> whose user is the
 *       booking's customer, its assigned worker, or an ADMIN. A bare {@code X-User-Id} / UUID bearer is never
 *       sufficient here.</li>
 *   <li>Participants are taken from the Redis assignment record the core writes when a booking is accepted
 *       ({@code customerId}, {@code userId}, {@code workerId}). A missing/unreadable record means <b>deny</b>
 *       (fail closed): the old behaviour of letting any authenticated subscriber in when no record existed was
 *       a cross-booking location leak.</li>
 * </ul>
 *
 * <p>GPS <em>ingest</em> identity is deliberately untouched: the worker app still falls back to an
 * {@code X-User-Id} session when it has no signed token, and changing that needs a coordinated app release.</p>
 */
@Component
public class TrackingAccessGuard {

    private static final Logger logger = LoggerFactory.getLogger(TrackingAccessGuard.class);
    private static final String ASSIGNMENT_PREFIX = "tracking:assignment:";
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final long WINDOW_MS = 60_000L;

    public record Caller(String userId, String role, boolean verifiedJwt) {
        public boolean isAdmin() {
            return "ADMIN".equalsIgnoreCase(role);
        }
    }

    private final JwtTokenValidator jwt;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int routeCallsPerIdentityPerMinute;
    private final int routeCallsGlobalPerMinute;

    private final Map<String, Deque<Long>> routeWindows = new ConcurrentHashMap<>();
    private final Deque<Long> globalRouteWindow = new ArrayDeque<>();
    private final AtomicInteger sweepCounter = new AtomicInteger();

    @Autowired
    public TrackingAccessGuard(
            JwtTokenValidator jwt,
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            @Value("${homeease.tracking.route-proxy.per-identity-per-minute:12}") int perIdentity,
            @Value("${homeease.tracking.route-proxy.global-per-minute:300}") int global) {
        this(jwt, redis, objectMapper, Clock.systemUTC(), perIdentity, global);
    }

    TrackingAccessGuard(JwtTokenValidator jwt, StringRedisTemplate redis, ObjectMapper objectMapper,
                        Clock clock, int perIdentity, int global) {
        this.jwt = jwt;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.routeCallsPerIdentityPerMinute = perIdentity;
        this.routeCallsGlobalPerMinute = global;
    }

    // ───────────────────────── identity ─────────────────────────

    /** @throws AuthenticationRequiredException unless the header carries a valid, unexpired, correctly signed JWT */
    public Caller requireVerifiedJwt(String authHeader) {
        Map<String, Object> claims = jwt.validateAndExtractClaims(authHeader);
        if (claims == null) {
            throw new AuthenticationRequiredException("A valid sign-in is required.");
        }
        Object raw = claims.get("userId") != null ? claims.get("userId") : claims.get("sub");
        if (raw == null) {
            throw new AuthenticationRequiredException("A valid sign-in is required.");
        }
        UUID userId;
        try {
            userId = UUID.fromString(raw.toString());
        } catch (IllegalArgumentException e) {
            throw new AuthenticationRequiredException("A valid sign-in is required.");
        }
        Object role = claims.get("role");
        return new Caller(userId.toString(), role == null ? null : role.toString(), true);
    }

    public Caller requireAdmin(String authHeader) {
        Caller caller = requireVerifiedJwt(authHeader);
        if (!caller.isAdmin()) {
            throw new SecurityException("Administrator access is required.");
        }
        return caller;
    }

    // ───────────────────────── booking participation ─────────────────────────

    /** REST read access: verified JWT + participant of {@code bookingId}. */
    public Caller requireParticipant(String authHeader, String bookingId) {
        Caller caller = requireVerifiedJwt(authHeader);
        assertParticipant(caller, bookingId);
        return caller;
    }

    /**
     * Partner-only actions (ending live tracking): verified JWT + the booking's ASSIGNED partner, or an admin. A
     * customer is a participant but must not be able to switch off the partner's live location.
     */
    public Caller requireAssignedWorker(String authHeader, String bookingId) {
        Caller caller = requireVerifiedJwt(authHeader);
        if (bookingId == null || !UUID_PATTERN.matcher(bookingId).matches()) {
            throw new IllegalArgumentException("Invalid bookingId.");
        }
        if (caller.isAdmin()) {
            return caller;
        }
        JsonNode assignment = readAssignment(bookingId);
        boolean worker = assignment != null
                && (matches(assignment, "userId", caller.userId()) || matches(assignment, "workerId", caller.userId()));
        if (!worker) {
            throw new SecurityException("Only the assigned service partner can do this.");
        }
        return caller;
    }

    /**
     * STOMP subscription access. {@code userId}/{@code role}/{@code verified} come from the CONNECT frame; only a
     * session established with a verified JWT may subscribe to booking-scoped topics.
     */
    public void authorizeSubscription(String userId, String role, boolean verified, String bookingId) {
        if (userId == null || userId.isBlank() || !verified) {
            logger.warn("Rejected tracking subscription for booking {}: no verified JWT on the session", bookingId);
            throw new AuthenticationRequiredException(
                    "A verified sign-in is required to subscribe to live tracking.");
        }
        assertParticipant(new Caller(userId, role, true), bookingId);
    }

    private void assertParticipant(Caller caller, String bookingId) {
        if (bookingId == null || !UUID_PATTERN.matcher(bookingId).matches()) {
            throw new IllegalArgumentException("Invalid bookingId.");
        }
        if (caller.isAdmin()) {
            return;
        }
        JsonNode assignment = readAssignment(bookingId);
        if (assignment == null) {
            logger.warn("Denied tracking access to booking {} for user {}: no assignment record", bookingId, caller.userId());
            throw new SecurityException("You are not authorized to view live tracking for this booking.");
        }
        boolean participant = matches(assignment, "customerId", caller.userId())
                || matches(assignment, "userId", caller.userId())
                || matches(assignment, "workerId", caller.userId());
        if (!participant) {
            logger.warn("Denied tracking access to booking {} for non-participant user {}", bookingId, caller.userId());
            throw new SecurityException("You are not authorized to view live tracking for this booking.");
        }
    }

    private static boolean matches(JsonNode node, String field, String userId) {
        String v = node.path(field).asText("");
        return !v.isBlank() && v.equalsIgnoreCase(userId);
    }

    private JsonNode readAssignment(String bookingId) {
        try {
            String json = redis.opsForValue().get(ASSIGNMENT_PREFIX + bookingId);
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readTree(json);
        } catch (Exception e) {
            // Fail closed: if participation cannot be established, nobody gets location data.
            logger.warn("Could not read tracking assignment for booking {}: {}", bookingId, e.getMessage());
            return null;
        }
    }

    // ───────────────────────── paid route proxy protection ─────────────────────────

    /**
     * Identity for the worker-navigation coordinate route endpoints. A verified JWT is preferred; a well-formed
     * UUID in {@code X-User-Id}/Bearer is still accepted because the worker app falls back to it, but anonymous
     * callers are refused. Cost is bounded by {@link #checkRoutePermit(String)}.
     */
    public String requireRouteIdentity(String authHeader, String xUserId) {
        Map<String, Object> claims = jwt.validateAndExtractClaims(authHeader);
        if (claims != null) {
            Object raw = claims.get("userId") != null ? claims.get("userId") : claims.get("sub");
            if (raw != null) {
                return raw.toString().toLowerCase();
            }
        }
        String candidate = xUserId != null ? xUserId.trim() : "";
        if (candidate.isEmpty() && authHeader != null && authHeader.startsWith("Bearer ")) {
            candidate = authHeader.substring(7).trim();
        }
        if (UUID_PATTERN.matcher(candidate).matches()) {
            return candidate.toLowerCase();
        }
        throw new AuthenticationRequiredException("A valid sign-in is required.");
    }

    /** Sliding-window cap per identity and globally, so the paid routing credential cannot be drained. */
    public void checkRoutePermit(String identity) {
        long now = clock.millis();
        long cutoff = now - WINDOW_MS;

        synchronized (globalRouteWindow) {
            prune(globalRouteWindow, cutoff);
            if (globalRouteWindow.size() >= routeCallsGlobalPerMinute) {
                logger.error("Global route-proxy cap ({}/min) reached; refusing further coordinate routing", routeCallsGlobalPerMinute);
                throw new RateLimitExceededException("Routing is busy. Please try again shortly.");
            }
        }
        Deque<Long> window = routeWindows.computeIfAbsent(identity, k -> new ArrayDeque<>());
        synchronized (window) {
            prune(window, cutoff);
            if (window.size() >= routeCallsPerIdentityPerMinute) {
                throw new RateLimitExceededException("Too many route requests. Please wait a moment.");
            }
            window.addLast(now);
        }
        synchronized (globalRouteWindow) {
            globalRouteWindow.addLast(now);
        }
        if (sweepCounter.incrementAndGet() % 500 == 0) {
            routeWindows.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    prune(e.getValue(), cutoff);
                    return e.getValue().isEmpty();
                }
            });
        }
    }

    private static void prune(Deque<Long> window, long cutoff) {
        while (!window.isEmpty() && window.peekFirst() < cutoff) {
            window.pollFirst();
        }
    }
}
