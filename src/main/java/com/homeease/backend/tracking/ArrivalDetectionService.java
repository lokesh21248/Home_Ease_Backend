package com.homeease.backend.tracking;

import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.routing.BookingRouteContextLoader;
import com.homeease.backend.routing.GeoMath;
import com.homeease.backend.routing.RouteContext;
import com.homeease.backend.service.BookingAssignmentService;
import com.homeease.backend.tracking.ArrivalGeofence.Evaluation;
import com.homeease.backend.tracking.ArrivalGeofence.Verdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Automatic, geofence-based arrival: every accepted partner GPS fix for a travelling booking is run through
 * {@link ArrivalGeofence}; a confirmed arrival moves the booking to ARRIVED through the same service method as the
 * manual button ({@link BookingAssignmentService#handleAutoArrival}), which issues the doorstep PIN and notifies the
 * customer. A one-off PARTNER_NEARBY event is published when the partner first comes within the nearby radius.
 *
 * <p>The booking context (status, destination) is cached for a few seconds per booking so a 3-second GPS cadence does
 * not turn into a database read per fix.</p>
 */
@Service
public class ArrivalDetectionService {

    private static final Logger log = LoggerFactory.getLogger(ArrivalDetectionService.class);
    private static final int MAX_CACHE = 5_000;

    private record CachedContext(RouteContext ctx, long loadedAtMs) {
    }

    private final BookingRouteContextLoader contextLoader;
    private final ObjectProvider<BookingAssignmentService> assignmentService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ArrivalGeofence geofence;
    private final Clock clock;
    private final boolean enabled;
    private final long contextTtlMs;

    private final ConcurrentHashMap<UUID, CachedContext> contexts = new ConcurrentHashMap<>();
    private final Set<UUID> nearbyAnnounced = ConcurrentHashMap.newKeySet();
    private final Set<UUID> arriving = ConcurrentHashMap.newKeySet();

    @Autowired
    public ArrivalDetectionService(BookingRouteContextLoader contextLoader,
                                   ObjectProvider<BookingAssignmentService> assignmentService,
                                   SimpMessagingTemplate messagingTemplate,
                                   @Value("${homeease.arrival.auto-detect.enabled:true}") boolean enabled,
                                   @Value("${homeease.arrival.radius-meters:75}") double radiusM,
                                   @Value("${homeease.arrival.nearby-radius-meters:500}") double nearbyM,
                                   @Value("${homeease.arrival.max-accuracy-meters:40}") double maxAccuracyM,
                                   @Value("${homeease.arrival.required-fixes:3}") int requiredFixes,
                                   @Value("${homeease.arrival.min-dwell-seconds:6}") long minDwellSeconds,
                                   @Value("${homeease.arrival.max-speed-mps:4.0}") double maxSpeedMps,
                                   @Value("${homeease.arrival.context-cache-seconds:10}") long contextCacheSeconds) {
        this(contextLoader, assignmentService, messagingTemplate,
                new ArrivalGeofence(new ArrivalGeofence.Config(radiusM, nearbyM, maxAccuracyM, requiredFixes,
                        minDwellSeconds * 1000L, maxSpeedMps)),
                Clock.systemUTC(), enabled, contextCacheSeconds * 1000L);
    }

    ArrivalDetectionService(BookingRouteContextLoader contextLoader,
                            ObjectProvider<BookingAssignmentService> assignmentService,
                            SimpMessagingTemplate messagingTemplate,
                            ArrivalGeofence geofence, Clock clock, boolean enabled, long contextTtlMs) {
        this.contextLoader = contextLoader;
        this.assignmentService = assignmentService;
        this.messagingTemplate = messagingTemplate;
        this.geofence = geofence;
        this.clock = clock;
        this.enabled = enabled;
        this.contextTtlMs = contextTtlMs;
    }

    public ArrivalGeofence.Config geofenceConfig() {
        return geofence.config();
    }

    /** @return the geofence verdict, or empty when the booking is not a travelling one (nothing evaluated) */
    public Optional<Evaluation> onFix(UUID bookingId, GpsFix fix) {
        if (!enabled || bookingId == null || fix == null) {
            return Optional.empty();
        }
        RouteContext ctx = context(bookingId);
        if (ctx == null || ctx.status() != BookingStage.ON_THE_WAY
                || !GeoMath.isUsableCoordinate(ctx.destLat(), ctx.destLng())) {
            return Optional.empty();
        }

        Evaluation e = geofence.evaluate(bookingId, fix, ctx.destLat(), ctx.destLng());

        if ((e.verdict() == Verdict.NEARBY || e.verdict() == Verdict.INSIDE_CONFIRMING)
                && nearbyAnnounced.add(bookingId)) {
            publishNearby(bookingId, e.distanceMeters());
        }
        if (e.verdict() == Verdict.ARRIVED && arriving.add(bookingId)) {
            try {
                assignmentService.getObject().handleAutoArrival(bookingId);
                log.info("[GEOFENCE_ARRIVED] booking {} auto-marked ARRIVED ({} m, {} consecutive fixes)",
                        bookingId, Math.round(e.distanceMeters()), e.consecutiveFixes());
            } catch (RuntimeException ex) {
                log.warn("[GEOFENCE_ARRIVAL_FAILED] booking {}: {}", bookingId, ex.getMessage());
            } finally {
                forget(bookingId);
                arriving.remove(bookingId);
            }
        }
        return Optional.of(e);
    }

    /** Clears all per-booking state (after arrival, cancellation or completion). */
    public void forget(UUID bookingId) {
        geofence.reset(bookingId);
        contexts.remove(bookingId);
    }

    private RouteContext context(UUID bookingId) {
        long now = clock.millis();
        CachedContext c = contexts.get(bookingId);
        if (c != null && now - c.loadedAtMs() < contextTtlMs) {
            return c.ctx();
        }
        RouteContext ctx;
        try {
            ctx = contextLoader.load(bookingId).orElse(null);
        } catch (RuntimeException e) {
            log.warn("Arrival detection could not load booking {}: {}", bookingId, e.getClass().getSimpleName());
            return null;
        }
        if (contexts.size() >= MAX_CACHE) {
            contexts.entrySet().removeIf(en -> now - en.getValue().loadedAtMs() >= contextTtlMs);
            if (contexts.size() >= MAX_CACHE) {
                contexts.clear();
            }
        }
        if (nearbyAnnounced.size() > MAX_CACHE) {
            nearbyAnnounced.clear();
        }
        contexts.put(bookingId, new CachedContext(ctx, now));
        return ctx;
    }

    private void publishNearby(UUID bookingId, double distanceMeters) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "PARTNER_NEARBY");
            event.put("bookingId", bookingId.toString());
            event.put("phase", TrackingPhase.PARTNER_NEARBY.name());
            event.put("distanceMeters", Math.round(distanceMeters));
            event.put("message", "Your service partner is nearby.");
            event.put("timestamp", clock.millis());
            messagingTemplate.convertAndSend("/topic/booking/" + bookingId, event);
        } catch (RuntimeException e) {
            log.debug("PARTNER_NEARBY broadcast skipped for {}: {}", bookingId, e.getMessage());
        }
    }
}
