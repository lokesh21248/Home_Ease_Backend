package com.homeease.backend.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.dto.BookingRouteResponse;
import com.homeease.backend.dto.BookingRouteResponse.GeoPoint;
import com.homeease.backend.exception.RoutingException;
import com.homeease.backend.exception.RoutingException.Code;
import com.homeease.backend.model.enums.BookingStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Produces the road route from an assigned worker's latest GPS fix to the booking's <em>persisted</em>
 * service address.
 *
 * <h3>Guarantees</h3>
 * <ul>
 *   <li><b>Origin</b> is the assigned worker's latest valid, fresh GPS fix (Redis {@code tracking:booking:{id}}).</li>
 *   <li><b>Destination</b> is {@code bookings.user_lat/user_lng}. The request carries no coordinates, so a
 *       client cannot steer the route, and the customer's own GPS / a pincode centroid is never substituted.</li>
 *   <li><b>Authorization</b>: only the booking's customer, its assigned worker, or an admin.</li>
 *   <li><b>Cost control</b>: a per-booking cache + minimum interval + single-flight guarantee that, however
 *       often clients call (GPS ticks every ~5 s), the provider is called at most once per
 *       {@code min-interval} per booking, plus negative caching after provider failures.</li>
 * </ul>
 */
@Service
public class BookingRouteService {

    private static final Logger log = LoggerFactory.getLogger(BookingRouteService.class);

    static final String LOCATION_KEY_PREFIX = "tracking:booking:";
    static final String PROVIDER = "google-routes-v2";
    private static final Set<BookingStage> TRAVEL_STAGES =
            EnumSet.of(BookingStage.ACCEPTED, BookingStage.CONFIRMED, BookingStage.ON_THE_WAY);
    private static final int MAX_CACHE_ENTRIES = 5000;
    private static final long FOLLOWER_WAIT_MS = 7_000L;
    private static final long MAX_NEGATIVE_BACKOFF_MS = 120_000L;

    private final BookingRouteContextLoader contextLoader;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final GoogleRoutesClient routesClient;
    private final Clock clock;

    private final long minIntervalMs;
    private final long offRouteMinIntervalMs;
    private final long maxAgeMs;
    private final double reuseRadiusMeters;
    private final long locationStaleMs;
    private final long errorBackoffMs;

    private final ConcurrentHashMap<UUID, CachedRoute> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<CachedRoute>> inFlight = new ConcurrentHashMap<>();

    @Autowired
    public BookingRouteService(
            BookingRouteContextLoader contextLoader,
            RedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper,
            GoogleRoutesClient routesClient,
            @Value("${homeease.routes.min-interval-seconds:20}") long minIntervalSeconds,
            @Value("${homeease.routes.off-route-min-interval-seconds:8}") long offRouteMinIntervalSeconds,
            @Value("${homeease.routes.max-age-seconds:60}") long maxAgeSeconds,
            @Value("${homeease.routes.reuse-radius-meters:60}") double reuseRadiusMeters,
            @Value("${homeease.routes.location-stale-seconds:120}") long locationStaleSeconds,
            @Value("${homeease.routes.error-backoff-seconds:20}") long errorBackoffSeconds) {
        this(contextLoader, redisTemplate, objectMapper, routesClient, Clock.systemUTC(),
                minIntervalSeconds, offRouteMinIntervalSeconds, maxAgeSeconds, reuseRadiusMeters,
                locationStaleSeconds, errorBackoffSeconds);
    }

    BookingRouteService(BookingRouteContextLoader contextLoader,
                        RedisTemplate<String, String> redisTemplate,
                        ObjectMapper objectMapper,
                        GoogleRoutesClient routesClient,
                        Clock clock,
                        long minIntervalSeconds,
                        long offRouteMinIntervalSeconds,
                        long maxAgeSeconds,
                        double reuseRadiusMeters,
                        long locationStaleSeconds,
                        long errorBackoffSeconds) {
        this.contextLoader = contextLoader;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.routesClient = routesClient;
        this.clock = clock;
        this.minIntervalMs = minIntervalSeconds * 1000L;
        this.offRouteMinIntervalMs = offRouteMinIntervalSeconds * 1000L;
        this.maxAgeMs = maxAgeSeconds * 1000L;
        this.reuseRadiusMeters = reuseRadiusMeters;
        this.locationStaleMs = locationStaleSeconds * 1000L;
        this.errorBackoffMs = errorBackoffSeconds * 1000L;
    }

    /**
     * @param callerUserId  user id taken from a <em>cryptographically verified</em> JWT
     * @param callerIsAdmin role claim of that verified JWT
     */
    public BookingRouteResponse getRoute(UUID bookingId, UUID callerUserId, boolean callerIsAdmin,
                                         RouteRefreshReason reason) {
        if (callerUserId == null) {
            throw new RoutingException(Code.JWT_REQUIRED, "A valid sign-in is required.");
        }
        RouteContext ctx = contextLoader.load(bookingId).orElseThrow(() ->
                new RoutingException(Code.BOOKING_NOT_FOUND, "Booking not found."));

        authorize(ctx, callerUserId, callerIsAdmin);

        if (ctx.status() == null || !TRAVEL_STAGES.contains(ctx.status())) {
            cache.remove(bookingId);
            throw new RoutingException(Code.TRACKING_NOT_ACTIVE,
                    "Live route is only available while the partner is travelling (booking is "
                            + (ctx.status() == null ? "UNKNOWN" : ctx.status().name()) + ").");
        }
        if (!ctx.hasAssignedWorker()) {
            throw new RoutingException(Code.WORKER_NOT_ASSIGNED, "No partner is assigned to this booking yet.");
        }
        if (!GeoMath.isUsableCoordinate(ctx.destLat(), ctx.destLng())) {
            log.warn("Booking {} has no usable persisted destination coordinates (lat={}, lng={})",
                    bookingId, ctx.destLat(), ctx.destLng());
            throw new RoutingException(Code.DESTINATION_UNAVAILABLE,
                    "The service address for this booking has no map location.");
        }

        long now = clock.millis();
        WorkerFix fix = readWorkerFix(bookingId, now);
        RouteRefreshReason effectiveReason = reason == null ? RouteRefreshReason.PERIODIC : reason;
        CachedRoute entry = resolve(ctx, fix, effectiveReason, now);
        return toResponse(ctx, entry, entry.fromCache);
    }

    // ───────────────────────── authorization ─────────────────────────

    private void authorize(RouteContext ctx, UUID callerUserId, boolean callerIsAdmin) {
        if (callerIsAdmin) {
            return;
        }
        boolean isCustomer = callerUserId.equals(ctx.customerUserId());
        boolean isAssignedWorker = callerUserId.equals(ctx.workerUserId())
                || callerUserId.equals(ctx.workerEntityId());
        if (!isCustomer && !isAssignedWorker) {
            log.warn("Route access denied: user {} is not a participant of booking {}", callerUserId, ctx.bookingId());
            throw new RoutingException(Code.NOT_BOOKING_PARTICIPANT,
                    "You are not allowed to view this booking's route.");
        }
    }

    // ───────────────────────── worker origin ─────────────────────────

    private record WorkerFix(double lat, double lng, long timestampMs) {
    }

    private WorkerFix readWorkerFix(UUID bookingId, long now) {
        String json;
        try {
            json = redisTemplate.opsForValue().get(LOCATION_KEY_PREFIX + bookingId);
        } catch (RuntimeException e) {
            log.warn("Redis read failed while resolving worker location for booking {}: {}", bookingId, e.getMessage());
            throw new RoutingException(Code.WORKER_LOCATION_UNAVAILABLE,
                    "The partner's live location is temporarily unavailable.", 5, e);
        }
        if (json == null || json.isBlank()) {
            throw new RoutingException(Code.WORKER_LOCATION_UNAVAILABLE,
                    "Waiting for the partner's live location.", 5);
        }
        try {
            JsonNode n = objectMapper.readTree(json);
            if (n.has("trackingActive") && !n.path("trackingActive").asBoolean(true)) {
                throw new RoutingException(Code.WORKER_LOCATION_UNAVAILABLE,
                        "Live tracking is not active for this booking.", 5);
            }
            Double lat = number(n, "lat", "latitude");
            Double lng = number(n, "lng", "longitude");
            if (!GeoMath.isUsableCoordinate(lat, lng)) {
                throw new RoutingException(Code.WORKER_LOCATION_UNAVAILABLE,
                        "The partner's reported location is not valid yet.", 5);
            }
            // Prefer the server receipt time (immune to device clock skew); fall back to the device timestamp.
            long ts = n.path("serverTimestamp").asLong(0L);
            if (ts <= 0) {
                ts = n.path("timestamp").asLong(0L);
            }
            if (ts <= 0) {
                throw new RoutingException(Code.WORKER_LOCATION_STALE,
                        "The partner's location has no timestamp and cannot be trusted.", 5);
            }
            long age = Math.max(0L, now - ts);
            if (age > locationStaleMs) {
                throw new RoutingException(Code.WORKER_LOCATION_STALE,
                        "The partner's last location update is " + (age / 1000) + "s old.", 5);
            }
            return new WorkerFix(lat, lng, ts);
        } catch (RoutingException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Unreadable worker location payload for booking {}: {}", bookingId, e.getClass().getSimpleName());
            throw new RoutingException(Code.WORKER_LOCATION_UNAVAILABLE,
                    "The partner's live location is temporarily unavailable.", 5, e);
        }
    }

    private static Double number(JsonNode n, String primary, String alternate) {
        JsonNode v = n.hasNonNull(primary) ? n.get(primary) : n.get(alternate);
        return v != null && v.isNumber() ? v.asDouble() : null;
    }

    // ───────────────────────── cache / throttle / single-flight ─────────────────────────

    /** Cache record. {@code fromCache} is only meaningful on the instance returned to a single caller. */
    private static final class CachedRoute {
        final ComputedRoute route;          // null only for a negative entry
        final double originLat;
        final double originLng;
        final long originRecordedAtMs;
        final double destLat;
        final double destLng;
        final long computedAtMs;
        final RoutingException error;       // non-null => negative entry
        final long errorUntilMs;
        boolean fromCache;

        CachedRoute(ComputedRoute route, double originLat, double originLng, long originRecordedAtMs,
                    double destLat, double destLng, long computedAtMs, RoutingException error, long errorUntilMs) {
            this.route = route;
            this.originLat = originLat;
            this.originLng = originLng;
            this.originRecordedAtMs = originRecordedAtMs;
            this.destLat = destLat;
            this.destLng = destLng;
            this.computedAtMs = computedAtMs;
            this.error = error;
            this.errorUntilMs = errorUntilMs;
        }

        long computedAtMs() {
            return computedAtMs;
        }

        boolean sameDestination(double lat, double lng) {
            return Math.abs(destLat - lat) < 1e-6 && Math.abs(destLng - lng) < 1e-6;
        }

        CachedRoute asCacheHit() {
            CachedRoute copy = new CachedRoute(route, originLat, originLng, originRecordedAtMs,
                    destLat, destLng, computedAtMs, error, errorUntilMs);
            copy.fromCache = true;
            return copy;
        }
    }

    private CachedRoute resolve(RouteContext ctx, WorkerFix fix, RouteRefreshReason reason, long now) {
        UUID id = ctx.bookingId();

        CachedRoute existing = cache.get(id);
        if (existing != null && existing.sameDestination(ctx.destLat(), ctx.destLng())) {
            if (existing.error != null && existing.errorUntilMs > now) {
                int remaining = (int) Math.max(1, (existing.errorUntilMs - now + 999) / 1000);
                throw new RoutingException(existing.error.getCode(), existing.error.getMessage(), remaining);
            }
            if (existing.route != null && canReuse(existing, fix, reason, now)) {
                return existing.asCacheHit();
            }
        }

        // Single-flight: concurrent callers for the same booking share one provider call.
        CompletableFuture<CachedRoute> mine = new CompletableFuture<>();
        CompletableFuture<CachedRoute> leader = inFlight.putIfAbsent(id, mine);
        if (leader != null) {
            return awaitLeader(leader);
        }
        try {
            ComputedRoute computed = routesClient.computeRoute(fix.lat(), fix.lng(), ctx.destLat(), ctx.destLng());
            CachedRoute fresh = new CachedRoute(computed, fix.lat(), fix.lng(), fix.timestampMs(),
                    ctx.destLat(), ctx.destLng(), clock.millis(), null, 0L);
            cache.put(id, fresh);
            evictIfNeeded(now);
            mine.complete(fresh);
            return fresh;
        } catch (RoutingException e) {
            long backoff = e.getRetryAfterSeconds() != null
                    ? Math.max(errorBackoffMs, e.getRetryAfterSeconds() * 1000L)
                    : errorBackoffMs;
            backoff = Math.min(backoff, MAX_NEGATIVE_BACKOFF_MS);
            cache.put(id, new CachedRoute(null, fix.lat(), fix.lng(), fix.timestampMs(),
                    ctx.destLat(), ctx.destLng(), clock.millis(), e, clock.millis() + backoff));
            mine.completeExceptionally(e);
            throw e;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(id, mine);
        }
    }

    private boolean canReuse(CachedRoute c, WorkerFix fix, RouteRefreshReason reason, long now) {
        long age = now - c.computedAtMs;
        long floor = reason == RouteRefreshReason.OFF_ROUTE ? offRouteMinIntervalMs : minIntervalMs;
        if (age < floor) {
            return true;
        }
        if (reason == RouteRefreshReason.OFF_ROUTE) {
            return false;
        }
        double movedMeters = GeoMath.haversineMeters(c.originLat, c.originLng, fix.lat(), fix.lng());
        return age < maxAgeMs && movedMeters < reuseRadiusMeters;
    }

    private CachedRoute awaitLeader(CompletableFuture<CachedRoute> leader) {
        try {
            return leader.get(FOLLOWER_WAIT_MS, TimeUnit.MILLISECONDS).asCacheHit();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RoutingException re) {
                throw re;
            }
            throw new RoutingException(Code.ROUTING_PROVIDER_ERROR,
                    "The routing service returned an unexpected error.", 15, e.getCause());
        } catch (TimeoutException e) {
            throw new RoutingException(Code.ROUTING_TIMEOUT, "The routing service did not respond in time.", 10, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RoutingException(Code.ROUTING_PROVIDER_ERROR, "Request interrupted.", 10, e);
        }
    }

    private void evictIfNeeded(long now) {
        if (cache.size() <= MAX_CACHE_ENTRIES) {
            return;
        }
        long cutoff = now - Math.max(maxAgeMs, minIntervalMs) * 3;
        cache.entrySet().removeIf(en -> en.getValue().computedAtMs < cutoff
                && (en.getValue().error == null || en.getValue().errorUntilMs < now));
    }

    // ───────────────────────── response ─────────────────────────

    private BookingRouteResponse toResponse(RouteContext ctx, CachedRoute c, boolean cached) {
        ComputedRoute r = c.route;
        return new BookingRouteResponse(
                ctx.bookingId(),
                ctx.status().name(),
                true,
                PROVIDER,
                r.encodedPolyline(),
                "ENCODED_POLYLINE_1E5",
                r.distanceMeters(),
                r.durationSeconds(),
                r.staticDurationSeconds(),
                new GeoPoint(c.originLat, c.originLng, c.originRecordedAtMs),
                new GeoPoint(ctx.destLat(), ctx.destLng(), null),
                Instant.ofEpochMilli(c.computedAtMs),
                c.computedAtMs,
                cached,
                (int) Math.max(1, minIntervalMs / 1000),
                r.steps());
    }
}
