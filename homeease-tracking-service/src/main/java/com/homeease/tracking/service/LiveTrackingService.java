package com.homeease.tracking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.tracking.dto.LocationPayload;
import com.homeease.tracking.exception.RateLimitExceededException;
import com.homeease.tracking.exception.TrackingAuthorizationUnavailableException;
import com.homeease.tracking.exception.TrackingNotActiveException;
import com.homeease.tracking.exception.TrackingPersistenceUnavailableException;
import com.homeease.tracking.util.GeohashUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class LiveTrackingService {

    private static final Logger logger = LoggerFactory.getLogger(LiveTrackingService.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final RestTemplate restTemplate;

    @Value("${homeease.google.maps.api-key:}")
    private String googleMapsApiKey;

    /**
     * When true (default) a GPS update is only accepted for a booking that has an
     * assignment record
     * (tracking:assignment:{id}, written by the core on acceptance). Set
     * TRACKING_REQUIRE_ASSIGNMENT=false only as
     * an emergency rollback or for local simulator testing against unassigned
     * booking ids.
     */
    @Value("${homeease.tracking.require-assignment:true}")
    private boolean requireAssignment = true;

    /** Same switch as the core and dispatch services (WORKER_PRESENCE_ENFORCE). */
    @Value("${homeease.worker.presence.enforce:false}")
    private boolean enforcePresence = false;

    @Value("${homeease.tracking.rate-limit.window-seconds:60}")
    private int windowSeconds;

    @Value("${homeease.tracking.rate-limit.max-updates:30}")
    private int maxUpdates;

    @Value("${homeease.tracking.rate-limit.movement-threshold-meters:15.0}")
    private double movementThresholdMeters;

    /**
     * Absolute per-partner ceiling, movement or not (default 60/min = one per
     * second; the app sends every 3-10 s).
     */
    @Value("${homeease.tracking.rate-limit.hard-max-updates-per-minute:60}")
    private int hardMaxUpdatesPerMinute = 60;

    private final ConcurrentMap<String, Deque<Long>> partnerHardWindows = new ConcurrentHashMap<>();

    /**
     * Maximum acceptable GPS horizontal accuracy in metres.
     * Updates with accuracy > this value are rejected to prevent noise injection.
     * Default: 50m. Set via TRACKING_MAX_ACCURACY_METERS env var.
     */
    @Value("${homeease.tracking.max-accuracy-meters:50.0}")
    private double maxAccuracyMeters;

    /**
     * Minimum movement required to not be classified as stationary noise.
     * Below this threshold, updates are suppressed if bearing is unchanged and
     * the update arrived within 3 seconds of the previous one.
     */
    private static final double STATIONARY_NOISE_THRESHOLD_METERS = 1.5;

    // Cache TTL: 2 hours for in-transit tracking data
    private static final Duration TRACKING_TTL = Duration.ofHours(2);
    private static final String GEO_WORKERS_KEY = "active_workers_geo";

    // Maximum entries per booking history deque (breadcrumbs)
    private static final int MAX_HISTORY_ENTRIES = 20;
    // Maximum active tracking sessions in L1 cache before pruning
    private static final int MAX_L1_CAPACITY = 5000;

    // =========================================================================
    // DSA 1: In-Memory L1 Hash Table (ConcurrentHashMap) for ultra-low latency O(1)
    // reads
    // =========================================================================
    private final ConcurrentMap<String, LocationPayload> activeLocationsMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> locationLastAccessMap = new ConcurrentHashMap<>();

    // =========================================================================
    // DSA 2: Per-booking monotonic sequence counter (AtomicLong, O(1))
    // Backed by Redis to preserve monotonicity across restarts & multi-node
    // instances.
    // =========================================================================
    private final ConcurrentMap<String, AtomicLong> sequenceCounters = new ConcurrentHashMap<>();

    // =========================================================================
    // DSA 3: Bounded Deque for recent GPS history (Breadcrumb Trail & Smoothing)
    // =========================================================================
    private final ConcurrentMap<String, Deque<LocationPayload>> locationHistoryMap = new ConcurrentHashMap<>();

    // =========================================================================
    // DSA 4: Bounded Expiring Deduplication Cache (LRU LinkedHashMap)
    // Drops exact duplicate packet retries from noisy cellular connections
    // =========================================================================
    private final Map<String, Long> deduplicationCache = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > 2000;
                }
            });

    // =========================================================================
    // DSA 5: Per-Booking Serialization + Async Coalescing Redis GEO Queue
    // =========================================================================
    public record TrackingTask(String bookingId, LocationPayload payload, String effectiveWorkerId) {
    }

    public record GeoWriteTask(String key, String bookingId, String effectiveWorkerId,
            double lat, double lng, long sequenceNumber, long createdAtMs) {
    }

    private record GeoRetryTask(GeoWriteTask task, long scheduledAtMs, int attempt)
            implements Comparable<GeoRetryTask> {
        @Override
        public int compareTo(GeoRetryTask o) {
            return Long.compare(this.scheduledAtMs, o.scheduledAtMs);
        }
    }

    private static final int MAX_DURABLE_PERSIST_ATTEMPTS = 3;
    private static final long[] DURABLE_RETRY_BACKOFF_MS = { 25L, 50L };
    private static final Set<String> TERMINAL_BOOKING_STATUSES = Set.of(
            "COMPLETED", "CANCELLED", "CANCELED", "ARRIVED", "IN_PROGRESS", "EXPIRED", "REJECTED", "FAILED");

    private final ConcurrentMap<String, Object> bookingLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, GeoWriteTask> pendingGeoWrites = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> lastPersistedGeoSeq = new ConcurrentHashMap<>();
    private final BlockingQueue<String> geoSignalQueue = new ArrayBlockingQueue<>(5000);
    private final PriorityQueue<GeoRetryTask> geoRetryQueue = new PriorityQueue<>();
    private final AtomicLong droppedEventsCount = new AtomicLong(0L);
    private final AtomicLong processedEventsCount = new AtomicLong(0L);

    private final ExecutorService queueExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LiveTracking-GeoWorker");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = true;

    // =========================================================================
    // DSA 7: Geohash Spatial Index (Morton Z-Order Space Filling Curve)
    // =========================================================================
    private final ConcurrentMap<String, Set<String>> spatialGeohashIndex = new ConcurrentHashMap<>();

    // =========================================================================
    // DSA 8: Sliding-Window Rate Limiter (Circular timestamp deque per partner)
    // =========================================================================
    private final ConcurrentMap<String, Deque<Long>> partnerRateLimitWindows = new ConcurrentHashMap<>();

    // =========================================================================
    // DSA 9: Graph Routing LRU Cache (Spatial Quantization)
    // =========================================================================
    private static class RouteCacheEntry {
        final Map<String, Object> data;
        final long expiresAt;

        RouteCacheEntry(Map<String, Object> data, long ttlMillis) {
            this.data = data;
            this.expiresAt = System.currentTimeMillis() + ttlMillis;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    private final Map<String, RouteCacheEntry> routeCache = Collections.synchronizedMap(
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, RouteCacheEntry> eldest) {
                    return size() > 100;
                }
            });

    public LiveTrackingService(StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            SimpMessagingTemplate messagingTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.messagingTemplate = messagingTemplate;
        this.restTemplate = new RestTemplate();
    }

    @PostConstruct
    public void init() {
        queueExecutor.submit(this::processQueuedGeoEvents);
        logger.info(
                "LiveTrackingService initialized: fail-closed assignment validation, durable Redis write-before-publish, monotonic sequence, and coalescing async GEO index");
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        queueExecutor.shutdown();
        try {
            if (!queueExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                queueExecutor.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            queueExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("LiveTrackingService gracefully shut down async GEO worker");
    }

    /**
     * Synchronously persists the latest authoritative booking GPS snapshot and
     * monotonic sequence
     * to Redis before any L1 update, WebSocket broadcast, Pub/Sub relay, or HTTP
     * 200 acknowledgement.
     */
    private void persistLatestLocationDurable(String bookingId, long seq, String serializedJson) {
        Exception lastError = null;
        String trackingKey = "tracking:booking:" + bookingId;
        String sharedLocationKey = "homeease:tracking:" + bookingId + ":location";
        String seqKey = "tracking:seq:" + bookingId;

        for (int attempt = 1; attempt <= MAX_DURABLE_PERSIST_ATTEMPTS; attempt++) {
            try {
                redisTemplate.opsForValue().set(trackingKey, serializedJson, TRACKING_TTL);
                redisTemplate.opsForValue().set(sharedLocationKey, serializedJson, Duration.ofSeconds(120));
                redisTemplate.opsForValue().set(seqKey, String.valueOf(seq), TRACKING_TTL);
                return;
            } catch (Exception e) {
                lastError = e;
                logger.warn("[TRACKING_DIAG] stage=REDIS_PERSIST_RETRY bookingId={} seq={} attempt={}/{} error={}",
                        bookingId, seq, attempt, MAX_DURABLE_PERSIST_ATTEMPTS, e.getMessage());
                if (attempt < MAX_DURABLE_PERSIST_ATTEMPTS) {
                    try {
                        Thread.sleep(DURABLE_RETRY_BACKOFF_MS[attempt - 1]);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new TrackingPersistenceUnavailableException(
                                "Interrupted while persisting GPS location for booking " + bookingId, ie);
                    }
                }
            }
        }

        throw new TrackingPersistenceUnavailableException(
                "Unable to durably persist GPS location in Redis for booking " + bookingId, lastError);
    }

    /**
     * Queues a coalesced Redis GEO index update for the worker so stale queued
     * writes or retries
     * can never overwrite newer coordinates or block booking-specific tracking
     * delivery.
     */
    private void enqueueCoalescedGeoWrite(String bookingId, String effectiveWorkerId, LocationPayload payload) {
        if (effectiveWorkerId == null || effectiveWorkerId.isBlank()
                || payload.getLat() == null || payload.getLng() == null) {
            return;
        }
        long seq = payload.getSequenceNumber() != null ? payload.getSequenceNumber() : 0L;
        String key = effectiveWorkerId.trim();
        GeoWriteTask candidate = new GeoWriteTask(
                key, bookingId, effectiveWorkerId.trim(),
                payload.getLat(), payload.getLng(), seq, System.currentTimeMillis());

        GeoWriteTask activeTask = pendingGeoWrites.compute(key, (k, existing) -> {
            if (existing == null) {
                return candidate;
            }
            if (Objects.equals(existing.bookingId(), bookingId)) {
                return seq >= existing.sequenceNumber() ? candidate : existing;
            }
            return candidate.createdAtMs() >= existing.createdAtMs() ? candidate : existing;
        });

        if (activeTask == candidate && !geoSignalQueue.offer(key)) {
            droppedEventsCount.incrementAndGet();
            logger.warn("[TRACKING_DIAG] stage=GEO_BACKPRESSURE bookingId={} note=latest_fix_coalesced", bookingId);
        }
    }

    /**
     * Background consumer thread for coalesced Redis GEO updates and bounded
     * backoff retries.
     */
    private void processQueuedGeoEvents() {
        while (running) {
            try {
                processPendingGeoRetries();

                String key = geoSignalQueue.poll(200, TimeUnit.MILLISECONDS);
                if (key != null) {
                    GeoWriteTask task = pendingGeoWrites.remove(key);
                    if (task != null && !persistGeoOnly(task)) {
                        scheduleGeoRetry(task, 1);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.warn("[TRACKING_DIAG] stage=GEO_WORKER_ERROR error={}", e.getMessage());
            }
        }
    }

    private void scheduleGeoRetry(GeoWriteTask task, int attempt) {
        if (attempt > 3) {
            droppedEventsCount.incrementAndGet();
            logger.warn("[TRACKING_DIAG] stage=GEO_RETRY_EXHAUSTED bookingId={} seq={}",
                    task.bookingId(), task.sequenceNumber());
            return;
        }
        GeoWriteTask newerPending = pendingGeoWrites.get(task.key());
        if (newerPending != null && Objects.equals(newerPending.bookingId(), task.bookingId())
                && newerPending.sequenceNumber() > task.sequenceNumber()) {
            return;
        }
        long backoffMs = (long) (500 * Math.pow(2, attempt - 1));
        long scheduledAt = System.currentTimeMillis() + backoffMs;
        synchronized (geoRetryQueue) {
            if (geoRetryQueue.size() < 1000) {
                geoRetryQueue.offer(new GeoRetryTask(task, scheduledAt, attempt));
            } else {
                droppedEventsCount.incrementAndGet();
                logger.warn("[TRACKING_DIAG] stage=GEO_RETRY_QUEUE_FULL bookingId={}", task.bookingId());
            }
        }
    }

    private void processPendingGeoRetries() {
        long now = System.currentTimeMillis();
        synchronized (geoRetryQueue) {
            while (!geoRetryQueue.isEmpty() && geoRetryQueue.peek().scheduledAtMs() <= now) {
                GeoRetryTask retry = geoRetryQueue.poll();
                if (!persistGeoOnly(retry.task())) {
                    scheduleGeoRetry(retry.task(), retry.attempt() + 1);
                }
            }
        }
    }

    private boolean persistGeoOnly(GeoWriteTask task) {
        try {
            GeoWriteTask newerPending = pendingGeoWrites.get(task.key());
            if (newerPending != null && Objects.equals(newerPending.bookingId(), task.bookingId())
                    && newerPending.sequenceNumber() > task.sequenceNumber()) {
                return true;
            }
            Long lastSeq = lastPersistedGeoSeq.get(task.key());
            if (lastSeq != null && task.sequenceNumber() > 0 && task.sequenceNumber() < lastSeq) {
                return true;
            }
            redisTemplate.opsForGeo().add(GEO_WORKERS_KEY,
                    new Point(task.lng(), task.lat()),
                    task.effectiveWorkerId());
            if (task.sequenceNumber() > 0) {
                lastPersistedGeoSeq.put(task.key(), task.sequenceNumber());
            }
            return true;
        } catch (Exception e) {
            logger.warn("[TRACKING_DIAG] stage=GEO_PERSIST_FAIL bookingId={} seq={} error={}",
                    task.bookingId(), task.sequenceNumber(), e.getMessage());
            return false;
        }
    }

    private String getEffectiveGoogleMapsKey() {
        if (googleMapsApiKey != null && !googleMapsApiKey.isBlank()) {
            return googleMapsApiKey.trim();
        }
        String envKey = System.getenv("GOOGLE_MAPS_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            return envKey.trim();
        }
        return "";
    }

    /**
     * Backward-compatible overload for existing calls.
     */
    public LocationPayload processLocationUpdate(String bookingId, LocationPayload payload) {
        return processLocationUpdate(bookingId, payload, null);
    }

    /**
     * Direct processing of location updates.
     */
    public LocationPayload processLocationUpdateDirect(String bookingId, LocationPayload payload,
            String authenticatedUserId) {
        return processLocationUpdate(bookingId, payload, authenticatedUserId);
    }

    /**
     * Records partner live coordinates in L1 Cache & Redis (Key-Value + Redis GEO)
     * and broadcasts live to customer WebSocket subscribers.
     *
     * Validates:
     * 1. Coordinate ranges (-90 to +90 lat, -180 to +180 lng, non-NaN)
     * 2. Bearing (0..360) and Speed sanity (0..160 km/h)
     * 3. Teleportation anomaly filter (rejects impossible speeds > 200 km/h)
     * 4. Deduplication filter (drops duplicate cellular retransmissions)
     * 5. Assignment & booking state in Redis (rejects if completed/cancelled or
     * unauthorized)
     * 6. Sliding-window rate limit (throttles spam while permitting legitimate
     * movement)
     * 7. Timestamp ordering (rejects stale updates, clamps future timestamps)
     * 8. Stationary duplicate suppression (avoids redundant broadcasts when
     * stopped)
     * 9. Geohash spatial bucketing & Monotonic atomic sequence generation
     * 10. Bounded breadcrumb history deque
     */
    public LocationPayload processLocationUpdate(String bookingId, LocationPayload payload,
            String authenticatedUserId) {
        if (payload == null) {
            throw new IllegalArgumentException("Payload cannot be null");
        }

        if (bookingId == null || bookingId.isBlank()) {
            throw new IllegalArgumentException("bookingId cannot be blank");
        }

        payload.setBookingId(bookingId);

        long receiptMs = System.currentTimeMillis();
        payload.setServerTimestamp(receiptMs);

        // 1. Validate coordinates
        Double lat = payload.getLat();
        Double lng = payload.getLng();
        if (lat == null || lng == null ||
                Double.isNaN(lat) || Double.isInfinite(lat) ||
                Double.isNaN(lng) || Double.isInfinite(lng) ||
                lat < -90.0 || lat > 90.0 ||
                lng < -180.0 || lng > 180.0) {
            throw new IllegalArgumentException(String.format(
                    "Invalid GPS coordinates: lat=%s, lng=%s (Must be between -90..90 and -180..180)", lat, lng));
        }

        // 1b. Validate GPS accuracy – reject fixes with excessive positional
        // uncertainty or negative values
        if (payload.getAccuracy() != null
                && (payload.getAccuracy() < 0.0 || payload.getAccuracy() > maxAccuracyMeters)) {
            logger.warn(
                    "[tracking] Rejected invalid/poor-accuracy GPS fix for booking {}: accuracy={}m (threshold={}m)",
                    bookingId, payload.getAccuracy(), maxAccuracyMeters);
            throw new IllegalArgumentException(String.format(
                    "GPS accuracy %.1fm is invalid or exceeds maximum allowed %.1fm — fix rejected",
                    payload.getAccuracy(), maxAccuracyMeters));
        }

        // 1c. Validate and normalize Bearing (0..360 degrees)
        if (payload.getBearing() != null) {
            double b = payload.getBearing();
            if (Double.isNaN(b) || Double.isInfinite(b)) {
                payload.setBearing(0.0);
            } else {
                b = b % 360.0;
                if (b < 0.0)
                    b += 360.0;
                payload.setBearing(Math.round(b * 10.0) / 10.0);
            }
        }

        // 1d. Validate Speed sanity
        if (payload.getSpeed() != null) {
            double s = payload.getSpeed();
            if (Double.isNaN(s) || Double.isInfinite(s) || s < 0.0) {
                payload.setSpeed(0.0);
            } else if (s > 45.0) { // > 162 km/h
                logger.warn("[tracking] High speed fix reported for booking {}: {} m/s ({} km/h)",
                        bookingId, s, Math.round(s * 3.6));
            }
        }

        // 2. Fail-closed booking assignment, identity & status validation in Redis
        String effectiveWorkerId;
        if (authenticatedUserId != null && !authenticatedUserId.isBlank()) {
            effectiveWorkerId = authenticatedUserId.trim();
            // Never trust client-supplied workerId/partnerId: always overwrite with
            // authenticated identity.
            payload.setWorkerId(effectiveWorkerId);
            payload.setPartnerId(effectiveWorkerId);
        } else {
            effectiveWorkerId = payload.getEffectiveId();
        }

        String assignmentKey = "tracking:assignment:" + bookingId;
        try {
            String assignmentJson = redisTemplate.opsForValue().get(assignmentKey);
            if (assignmentJson != null && !assignmentJson.isBlank()) {
                JsonNode assignmentNode = objectMapper.readTree(assignmentJson);
                if (assignmentNode == null || !assignmentNode.isObject()) {
                    throw new SecurityException("Invalid booking assignment metadata for booking " + bookingId);
                }

                // Require an explicit worker/user identity in the assignment record
                String assignedUserId = assignmentNode.path("userId").asText("").trim();
                String assignedWorkerId = assignmentNode.path("workerId").asText("").trim();
                if (assignedUserId.isBlank() && assignedWorkerId.isBlank()) {
                    logger.warn("[TRACKING_DIAG] stage=ASSIGNMENT_NO_WORKER bookingId={}", bookingId);
                    throw new SecurityException(
                            "Invalid booking assignment metadata: missing worker identity for booking " + bookingId);
                }

                // Verify caller identity BEFORE revealing booking status
                if (authenticatedUserId == null || authenticatedUserId.isBlank()) {
                    throw new SecurityException(
                            "Authentication required: unauthenticated location update rejected for assigned booking "
                                    + bookingId);
                }
                String callerId = authenticatedUserId.trim();
                boolean isAssigned = (!assignedUserId.isBlank() && assignedUserId.equalsIgnoreCase(callerId))
                        || (!assignedWorkerId.isBlank() && assignedWorkerId.equalsIgnoreCase(callerId));
                if (!isAssigned) {
                    logger.warn("[TRACKING_DIAG] stage=ASSIGNMENT_WRONG_WORKER bookingId={} caller={}", bookingId,
                            callerId);
                    throw new SecurityException("Authenticated user is not assigned to booking " + bookingId);
                }

                String bookingStatus = assignmentNode.path("status").asText("").trim();
                String normalizedStatus = bookingStatus.toUpperCase(Locale.ROOT);
                boolean trackingActiveFlag = assignmentNode.has("trackingActive")
                        ? assignmentNode.path("trackingActive").asBoolean(true)
                        : true;

                if (TERMINAL_BOOKING_STATUSES.contains(normalizedStatus)) {
                    evictBookingState(bookingId);
                    logger.info("[TRACKING_DIAG] stage=BOOKING_TERMINAL bookingId={} status={}", bookingId,
                            normalizedStatus);
                    throw new IllegalStateException(
                            "Live tracking has ended: booking " + bookingId + " is " + normalizedStatus);
                }

                if (!"ON_THE_WAY".equals(normalizedStatus) || !trackingActiveFlag) {
                    if (!trackingActiveFlag && normalizedStatus.isBlank()) {
                        evictBookingState(bookingId);
                        throw new IllegalStateException(
                                "Live tracking has ended: booking " + bookingId + " is INACTIVE");
                    }
                    String statusLabel = normalizedStatus.isBlank() ? "UNKNOWN" : normalizedStatus;
                    logger.info("[TRACKING_DIAG] stage=BOOKING_NOT_ON_THE_WAY bookingId={} status={} trackingActive={}",
                            bookingId, statusLabel, trackingActiveFlag);
                    throw new TrackingNotActiveException(statusLabel,
                            "Live tracking requires ON_THE_WAY status for booking " + bookingId
                                    + " (status=" + statusLabel + ", trackingActive=" + trackingActiveFlag + ")");
                }
            } else if (requireAssignment) {
                // Fail closed: without an active Redis assignment record, updates are never
                // accepted or broadcast.
                logger.warn("[TRACKING_DIAG] stage=ASSIGNMENT_MISSING bookingId={}", bookingId);
                throw new SecurityException("No active assignment for booking " + bookingId
                        + ": location update rejected");
            }
        } catch (IllegalArgumentException | IllegalStateException | SecurityException
                | TrackingAuthorizationUnavailableException e) {
            throw e;
        } catch (JsonProcessingException e) {
            logger.warn("[TRACKING_DIAG] stage=ASSIGNMENT_PARSE_FAIL bookingId={} error={}", bookingId, e.getMessage());
            throw new SecurityException("Invalid booking assignment metadata for booking " + bookingId);
        } catch (Exception e) {
            logger.error("[TRACKING_DIAG] stage=ASSIGNMENT_REDIS_FAIL bookingId={} error={}", bookingId,
                    e.getMessage());
            throw new TrackingAuthorizationUnavailableException(
                    "Unable to validate booking assignment in Redis for booking " + bookingId, e);
        }

        // 3-7. Serialize per-booking ordering so concurrent pings or retries never
        // overwrite newer coordinates.
        Object bookingLock = bookingLocks.computeIfAbsent(bookingId, k -> new Object());
        LocationPayload broadcastPayload;
        long broadcastStartMs;
        long seq;

        synchronized (bookingLock) {
            long now = System.currentTimeMillis();
            long clientTimestamp = payload.getTimestamp() != null ? payload.getTimestamp() : now;
            long effectiveTimestamp = Math.min(clientTimestamp, now + 5000);
            payload.setTimestamp(effectiveTimestamp);

            // 3b. Bounded Expiring Deduplication Check (check before persisting; commit
            // only after durable save)
            String dedupFingerprint = String.format(Locale.US, "%s:%.6f:%.6f:%d", bookingId, lat, lng,
                    effectiveTimestamp);
            Long prevSeen;
            synchronized (deduplicationCache) {
                prevSeen = deduplicationCache.get(dedupFingerprint);
            }
            if (prevSeen != null && (now - prevSeen) < 60_000) {
                logger.debug("[TRACKING_DIAG] stage=DEDUP_DROP bookingId={}", bookingId);
                LocationPayload cached = activeLocationsMap.get(bookingId);
                return cached != null ? cached : payload;
            }

            LocationPayload latestExisting = loadLatestLocationFromCacheOrRedis(bookingId);
            if (latestExisting != null && latestExisting.getTimestamp() != null) {
                if (effectiveTimestamp <= latestExisting.getTimestamp()) {
                    logger.warn("[TRACKING_DIAG] stage=STALE_TS_REJECT bookingId={} incomingTs={} cachedTs={}",
                            bookingId, effectiveTimestamp, latestExisting.getTimestamp());
                    return latestExisting;
                }

                // 3c. Teleportation Anomaly Filter
                long dtMs = effectiveTimestamp - latestExisting.getTimestamp();
                if (dtMs > 0 && dtMs < 300_000) {
                    double distanceMeters = haversine(latestExisting.getLat(), latestExisting.getLng(), lat, lng)
                            * 1000.0;
                    double speedMps = distanceMeters / (dtMs / 1000.0);
                    if (distanceMeters > 300.0 && speedMps > 55.0) {
                        logger.warn("[TRACKING_DIAG] stage=TELEPORT_REJECT bookingId={} movedMeters={} dtMs={} kmh={}",
                                bookingId, Math.round(distanceMeters), dtMs, Math.round(speedMps * 3.6));
                        return latestExisting;
                    }
                }

                // 3d. Stationary noise suppression: threshold 1.5m
                double movedDistMeters = haversine(latestExisting.getLat(), latestExisting.getLng(), lat, lng) * 1000.0;
                boolean bearingSame = Objects.equals(latestExisting.getBearing(), payload.getBearing());
                if (movedDistMeters < STATIONARY_NOISE_THRESHOLD_METERS && bearingSame &&
                        (effectiveTimestamp - latestExisting.getTimestamp() < 3000)) {
                    latestExisting.setTimestamp(effectiveTimestamp);
                    activeLocationsMap.put(bookingId, latestExisting);
                    locationLastAccessMap.put(bookingId, now);
                    logger.debug("[TRACKING_DIAG] stage=STATIONARY_SUPPRESS bookingId={}", bookingId);
                    return latestExisting;
                }
            }

            // 4. Sliding-Window Rate Limiting (DSA 8)
            String rateLimitKey = (effectiveWorkerId != null && !effectiveWorkerId.isBlank()) ? effectiveWorkerId
                    : bookingId;
            checkSlidingWindowRateLimit(rateLimitKey, lat, lng, latestExisting);

            // 5. Never invent straight-line distance or ETA on GPS frames; routes come from
            // Core Google Routes v2.
            payload.setDistanceKm(null);
            payload.setEtaMinutes(null);
            payload.setTrackingActive(true);
            payload.setStatus("TRACKING");

            // 5b. Compute Geohash Spatial Key (~153m street block precision)
            String geohash = GeohashUtils.encode(lat, lng, 7);
            payload.setGeohash(geohash);

            // 5c. Assign monotonic sequence number strictly above both local counter &
            // persisted Redis state
            AtomicLong counter = sequenceCounters.computeIfAbsent(bookingId, k -> new AtomicLong(0L));
            long floor = 0L;
            if (latestExisting != null && latestExisting.getSequenceNumber() != null) {
                floor = Math.max(floor, latestExisting.getSequenceNumber());
            }
            try {
                String redisSeqRaw = redisTemplate.opsForValue().get("tracking:seq:" + bookingId);
                if (redisSeqRaw != null && !redisSeqRaw.isBlank()) {
                    floor = Math.max(floor, Long.parseLong(redisSeqRaw.trim()));
                }
            } catch (Exception ignored) {
            }
            final long seqFloor = floor;
            counter.updateAndGet(cur -> Math.max(cur, seqFloor));
            seq = counter.incrementAndGet();
            payload.setSequenceNumber(seq);

            String serializedPayload;
            try {
                serializedPayload = objectMapper.writeValueAsString(payload);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Failed to serialize GPS payload", e);
            }

            // 6. Durable Synchronous Redis Persistence BEFORE L1 cache mutation, WebSocket
            // broadcast, or HTTP 200.
            // On Redis outage this throws TrackingPersistenceUnavailableException (HTTP
            // 503) so the worker retains
            // its newest GPS fix and no customer receives an unpersisted broadcast.
            long persistStartMs = System.currentTimeMillis();
            persistLatestLocationDurable(bookingId, seq, serializedPayload);
            long persistLatencyMs = System.currentTimeMillis() - persistStartMs;

            // Record deduplication only after durable persistence succeeds so 503 retries
            // are never suppressed.
            synchronized (deduplicationCache) {
                deduplicationCache.put(dedupFingerprint, now);
            }

            // Update Spatial Geohash Index & L1 Cache (DSA 1 & DSA 3) only after durable
            // persistence
            spatialGeohashIndex.computeIfAbsent(geohash, k -> ConcurrentHashMap.newKeySet()).add(bookingId);
            if (latestExisting != null && latestExisting.getGeohash() != null
                    && !latestExisting.getGeohash().equals(geohash)) {
                Set<String> oldBucket = spatialGeohashIndex.get(latestExisting.getGeohash());
                if (oldBucket != null) {
                    oldBucket.remove(bookingId);
                    if (oldBucket.isEmpty()) {
                        spatialGeohashIndex.remove(latestExisting.getGeohash());
                    }
                }
            }

            activeLocationsMap.put(bookingId, payload);
            locationLastAccessMap.put(bookingId, now);

            Deque<LocationPayload> history = locationHistoryMap.computeIfAbsent(bookingId,
                    k -> new ConcurrentLinkedDeque<>());
            history.addLast(payload);
            while (history.size() > MAX_HISTORY_ENTRIES) {
                history.pollFirst();
            }

            if (activeLocationsMap.size() > MAX_L1_CAPACITY) {
                pruneStaleL1Entries(now);
            }

            processedEventsCount.incrementAndGet();
            broadcastPayload = payload;

            // 7. Broadcast to booking-specific STOMP topics and Redis Pub/Sub bridge AFTER
            // Redis persist
            broadcastStartMs = System.currentTimeMillis();
            try {
                messagingTemplate.convertAndSend("/topic/tracking/" + bookingId, broadcastPayload);
                messagingTemplate.convertAndSend("/topic/track/" + bookingId, broadcastPayload);
                messagingTemplate.convertAndSend("/topic/tracking/booking/" + bookingId, broadcastPayload);

                // Distributed WebSocket Relay: publish to Redis pub/sub channel so
                // homeease-core (:8080) forwards
                try {
                    Map<String, Object> bridgeMsg = new HashMap<>();
                    bridgeMsg.put("destination", "/topic/tracking/" + bookingId);
                    bridgeMsg.put("bookingId", bookingId);
                    bridgeMsg.put("payload", broadcastPayload);
                    redisTemplate.convertAndSend("ws:broadcast:tracking", objectMapper.writeValueAsString(bridgeMsg));
                } catch (Exception redisEx) {
                    logger.warn("[TRACKING_DIAG] stage=PUBSUB_RELAY_WARN bookingId={} seq={} error={}",
                            bookingId, seq, redisEx.getMessage());
                }

                long broadcastLatencyMs = System.currentTimeMillis() - broadcastStartMs;
                long e2eLatencyMs = System.currentTimeMillis() - receiptMs;
                logger.debug(
                        "[TRACKING_DIAG] stage=PERSIST_AND_BROADCAST bookingId={} seq={} persistMs={} broadcastMs={} e2eMs={}",
                        bookingId, seq, persistLatencyMs, broadcastLatencyMs, e2eLatencyMs);
            } catch (Exception e) {
                logger.warn("[TRACKING_DIAG] stage=STOMP_BROADCAST_WARN bookingId={} seq={} error={}",
                        bookingId, seq, e.getMessage());
            }

            // 8. Coalesced Async Redis GEO Update (separate from critical booking location
            // path)
            enqueueCoalescedGeoWrite(bookingId, effectiveWorkerId, broadcastPayload);
        }

        return broadcastPayload;
    }

    private void evictBookingState(String bookingId) {
        activeLocationsMap.remove(bookingId);
        locationLastAccessMap.remove(bookingId);
        locationHistoryMap.remove(bookingId);
        sequenceCounters.remove(bookingId);
    }

    private void pruneStaleL1Entries(long now) {
        long cutoff = now - TRACKING_TTL.toMillis();
        locationLastAccessMap.entrySet().removeIf(entry -> {
            if (entry.getValue() < cutoff) {
                String bId = entry.getKey();
                activeLocationsMap.remove(bId);
                locationHistoryMap.remove(bId);
                sequenceCounters.remove(bId);
                return true;
            }
            return false;
        });
    }

    /**
     * Sliding-Window Rate Limiter (DSA 8)
     */
    private void checkSlidingWindowRateLimit(String rateLimitKey, double lat, double lng, LocationPayload previous) {
        long nowHard = System.currentTimeMillis();
        Deque<Long> hard = partnerHardWindows.computeIfAbsent(rateLimitKey, k -> new ConcurrentLinkedDeque<>());
        synchronized (hard) {
            while (!hard.isEmpty() && hard.peekFirst() < nowHard - 60_000L) {
                hard.pollFirst();
            }
            if (hard.size() >= hardMaxUpdatesPerMinute) {
                throw new RateLimitExceededException(String.format(
                        "GPS update hard limit exceeded for partner %s: %d updates per minute", rateLimitKey,
                        hardMaxUpdatesPerMinute));
            }
            hard.addLast(nowHard);
        }

        if (previous != null && previous.getLat() != null && previous.getLng() != null) {
            double distanceMovedMeters = haversine(previous.getLat(), previous.getLng(), lat, lng) * 1000.0;
            if (distanceMovedMeters >= movementThresholdMeters) {
                return;
            }
        }

        long now = System.currentTimeMillis();
        long windowStart = now - (windowSeconds * 1000L);

        Deque<Long> window = partnerRateLimitWindows.computeIfAbsent(rateLimitKey, k -> new ConcurrentLinkedDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst() < windowStart) {
                window.pollFirst();
            }

            if (window.size() >= maxUpdates) {
                throw new RateLimitExceededException(String.format(
                        "GPS update rate limit exceeded for partner %s: %d updates within %ds window",
                        rateLimitKey, maxUpdates, windowSeconds));
            }

            window.addLast(now);
        }
    }

    /**
     * Retrieves the latest known location for a booking.
     * Uses in-memory L1 cache (O(1)) with Redis L2 fallback after verifying active
     * assignment state.
     */
    public LocationPayload getLatestLocation(String bookingId) {
        if (bookingId == null || bookingId.isBlank()) {
            return null;
        }

        String assignmentKey = "tracking:assignment:" + bookingId;
        try {
            String assignmentJson = redisTemplate.opsForValue().get(assignmentKey);
            if (assignmentJson != null && !assignmentJson.isBlank()) {
                JsonNode assignmentNode = objectMapper.readTree(assignmentJson);
                String status = assignmentNode.path("status").asText("").trim();
                String normalizedStatus = status.toUpperCase(Locale.ROOT);
                boolean trackingActive = assignmentNode.has("trackingActive")
                        ? assignmentNode.path("trackingActive").asBoolean(true)
                        : true;
                if (TERMINAL_BOOKING_STATUSES.contains(normalizedStatus) || !trackingActive) {
                    evictBookingState(bookingId);
                    return LocationPayload.builder()
                            .bookingId(bookingId)
                            .trackingActive(false)
                            .status(!status.isBlank() ? status : "CLOSED")
                            .message("Tracking ended: booking status is " + (!status.isBlank() ? status : "INACTIVE"))
                            .build();
                }
            }
        } catch (Exception e) {
            logger.warn("Could not check assignment state in getLatestLocation for booking {}: {}", bookingId,
                    e.getMessage());
        }

        return loadLatestLocationFromCacheOrRedis(bookingId);
    }

    private LocationPayload loadLatestLocationFromCacheOrRedis(String bookingId) {
        // 1. Check in-memory L1 cache (DSA 1)
        LocationPayload cached = activeLocationsMap.get(bookingId);
        if (cached != null) {
            Long lastAccess = locationLastAccessMap.get(bookingId);
            if (lastAccess != null && (System.currentTimeMillis() - lastAccess) < TRACKING_TTL.toMillis()) {
                locationLastAccessMap.put(bookingId, System.currentTimeMillis());
                return cached;
            } else {
                activeLocationsMap.remove(bookingId);
                locationLastAccessMap.remove(bookingId);
            }
        }

        // 2. Check Redis L2 storage: homeease:tracking:{bookingId}:location first,
        // fallback to tracking:booking:{bookingId}
        try {
            String raw = redisTemplate.opsForValue().get("homeease:tracking:" + bookingId + ":location");
            if (raw == null || raw.isBlank()) {
                raw = redisTemplate.opsForValue().get("tracking:booking:" + bookingId);
            }
            if (raw != null && !raw.isBlank()) {
                String trimmed = raw.trim();
                LocationPayload payload = null;
                if (trimmed.startsWith("{")) {
                    payload = objectMapper.readValue(trimmed, LocationPayload.class);
                } else if (trimmed.contains(",")) {
                    String[] parts = trimmed.split(",");
                    if (parts.length >= 2) {
                        payload = LocationPayload.builder()
                                .bookingId(bookingId)
                                .lat(Double.parseDouble(parts[0].trim()))
                                .lng(Double.parseDouble(parts[1].trim()))
                                .timestamp(System.currentTimeMillis())
                                .trackingActive(true)
                                .status("TRACKING")
                                .build();
                    }
                }

                if (payload != null) {
                    activeLocationsMap.put(bookingId, payload);
                    locationLastAccessMap.put(bookingId, System.currentTimeMillis());
                    return payload;
                }
            }
        } catch (Exception e) {
            logger.error("Error reading live location from Redis for booking {}: {}", bookingId, e.getMessage());
        }
        return null;
    }

    /**
     * DSA 3: Retrieves recent GPS breadcrumb history (bounded to last 20 fixes).
     */
    public List<LocationPayload> getRecentHistory(String bookingId) {
        if (bookingId == null || bookingId.isBlank()) {
            return Collections.emptyList();
        }
        Deque<LocationPayload> history = locationHistoryMap.get(bookingId);
        if (history == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(history);
    }

    /**
     * DSA 8: Proximity Query using Redis GEO (Nearest active workers).
     */
    public List<Map<String, Object>> findNearestWorkers(double lat, double lng, double radiusKm, int limit) {
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            Circle circle = new Circle(new Point(lng, lat), new Distance(radiusKm, Metrics.KILOMETERS));
            RedisGeoCommands.GeoRadiusCommandArgs args = RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                    .includeDistance()
                    .includeCoordinates()
                    .sortAscending()
                    .limit(limit > 0 ? limit : 10);

            GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults = redisTemplate.opsForGeo()
                    .radius(GEO_WORKERS_KEY, circle, args);
            if (geoResults != null) {
                // GEO members never expire: keep only workers with a live worker:heartbeat:<id>
                // key
                // (written by the core with the configured TTL). One MGET for all candidates.
                Set<String> alive = null;
                if (enforcePresence) {
                    List<String> names = new ArrayList<>();
                    for (var r : geoResults) {
                        names.add(r.getContent().getName());
                    }
                    alive = new HashSet<>();
                    if (!names.isEmpty()) {
                        List<String> keys = new ArrayList<>(names.size());
                        for (String n : names) {
                            keys.add("worker:heartbeat:" + n);
                        }
                        List<String> values = redisTemplate.opsForValue().multiGet(keys);
                        for (int i = 0; values != null && i < values.size() && i < names.size(); i++) {
                            if (values.get(i) != null) {
                                alive.add(names.get(i));
                            }
                        }
                    }
                }
                for (var geoResult : geoResults) {
                    if (alive != null && !alive.contains(geoResult.getContent().getName())) {
                        continue;
                    }
                    Map<String, Object> item = new HashMap<>();
                    item.put("workerId", geoResult.getContent().getName());
                    item.put("distanceKm", Math.round(geoResult.getDistance().getValue() * 100.0) / 100.0);
                    if (geoResult.getContent().getPoint() != null) {
                        item.put("lat", geoResult.getContent().getPoint().getY());
                        item.put("lng", geoResult.getContent().getPoint().getX());
                    }
                    results.add(item);
                }
            }
        } catch (Exception e) {
            logger.warn("Redis GEO proximity query failed: {}", e.getMessage());
        }
        return results;
    }

    /**
     * Calculates distance (Haversine formula in KM) and estimated time of arrival
     * (ETA in minutes).
     */
    public Map<String, Object> calculateDistanceAndEta(String bookingId, double customerLat, double customerLng) {
        Map<String, Object> response = new HashMap<>();
        LocationPayload workerLoc = getLatestLocation(bookingId);

        if (workerLoc == null || workerLoc.getLat() == null || workerLoc.getLng() == null) {
            response.put("found", false);
            response.put("bookingId", bookingId);
            response.put("message", "Worker live location not yet available");
            return response;
        }

        if (Boolean.FALSE.equals(workerLoc.getTrackingActive())) {
            response.put("found", false);
            response.put("bookingId", bookingId);
            response.put("status", workerLoc.getStatus());
            response.put("message", "Booking tracking is closed (" + workerLoc.getStatus() + ")");
            return response;
        }

        double distanceKm = haversine(workerLoc.getLat(), workerLoc.getLng(), customerLat, customerLng);
        int etaMinutes = Math.max(1, (int) Math.round((distanceKm / 25.0) * 60.0) + 2);

        long now = System.currentTimeMillis();
        long ageMs = now - (workerLoc.getTimestamp() != null ? workerLoc.getTimestamp() : now);
        boolean isStale = ageMs > 45_000;

        response.put("found", true);
        response.put("bookingId", bookingId);
        response.put("workerId", workerLoc.getWorkerId());
        response.put("workerLat", workerLoc.getLat());
        response.put("workerLng", workerLoc.getLng());
        response.put("bearing", workerLoc.getBearing() != null ? workerLoc.getBearing() : 0.0);
        response.put("customerLat", customerLat);
        response.put("customerLng", customerLng);
        response.put("distanceKm", Math.round(distanceKm * 100.0) / 100.0);
        response.put("etaMinutes", etaMinutes);
        response.put("lastUpdated", workerLoc.getTimestamp());
        response.put("trackingActive", true);
        response.put("isStale", isStale);
        response.put("ageSeconds", Math.max(0, ageMs / 1000));
        if (isStale) {
            response.put("staleNotice", "Location signal may be delayed");
        }

        return response;
    }

    /**
     * Explicitly stops tracking for a booking and evicts cached entries.
     */
    public void stopTracking(String bookingId, String reason) {
        if (bookingId == null || bookingId.isBlank())
            return;
        evictBookingState(bookingId);
        bookingLocks.remove(bookingId);

        try {
            redisTemplate.delete("homeease:tracking:" + bookingId + ":location");
            redisTemplate.delete("tracking:booking:" + bookingId);
        } catch (Exception ignored) {
        }

        LocationPayload closed = LocationPayload.builder()
                .bookingId(bookingId)
                .trackingActive(false)
                .status(reason != null ? reason : "STOPPED")
                .message("Live tracking stopped: " + reason)
                .build();

        try {
            messagingTemplate.convertAndSend("/topic/tracking/" + bookingId, closed);
            messagingTemplate.convertAndSend("/topic/track/" + bookingId, closed);
            messagingTemplate.convertAndSend("/topic/tracking/booking/" + bookingId, closed);

            try {
                Map<String, Object> bridgeMsg = new HashMap<>();
                bridgeMsg.put("destination", "/topic/tracking/" + bookingId);
                bridgeMsg.put("bookingId", bookingId);
                bridgeMsg.put("payload", closed);
                redisTemplate.convertAndSend("ws:broadcast:tracking", objectMapper.writeValueAsString(bridgeMsg));
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        }
    }

    // REMOVED getBookingRoute(): it accepted the destination from the client and
    // had no booking authorization.
    // The authoritative booking route is served by the core service
    // (BookingRouteService).

    /**
     * Computes driving route using Google Maps with LRU Cache (DSA 9).
     * Quantizes coordinates to ~100m grid bucket to avoid repeated API requests for
     * slight movements.
     */
    public Map<String, Object> getDrivingRoute(double startLat, double startLng, double destLat, double destLng) {
        if (Double.isNaN(startLat) || Double.isNaN(startLng) || Double.isNaN(destLat) || Double.isNaN(destLng) ||
                startLat < -90.0 || startLat > 90.0 || destLat < -90.0 || destLat > 90.0 ||
                startLng < -180.0 || startLng > 180.0 || destLng < -180.0 || destLng > 180.0) {
            return Map.of(
                    "found", false,
                    "provider", "google",
                    "message", "Invalid coordinate bounds provided for routing");
        }

        String cacheKey = String.format(Locale.US, "%.3f,%.3f->%.3f,%.3f", startLat, startLng, destLat, destLng);
        RouteCacheEntry cached = routeCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            logger.debug("Returning cached driving route for key {}", cacheKey);
            return cached.data;
        }

        String googleKey = getEffectiveGoogleMapsKey();
        if (googleKey.isBlank()) {
            logger.warn("Road routing skipped: GOOGLE_MAPS_API_KEY is not configured");
            return Map.of("found", false, "provider", "google", "message", "Google Maps API key not configured");
        }

        Map<String, Object> routesV2 = getGoogleRoutesV2(startLat, startLng, destLat, destLng, googleKey);
        if (routesV2 != null && Boolean.TRUE.equals(routesV2.get("found"))) {
            routeCache.put(cacheKey, new RouteCacheEntry(routesV2, 300_000L)); // 5 min TTL
            return routesV2;
        }

        Map<String, Object> googleRes = getGoogleRoadRoute(startLat, startLng, destLat, destLng, googleKey);
        if (googleRes != null && Boolean.TRUE.equals(googleRes.get("found"))) {
            routeCache.put(cacheKey, new RouteCacheEntry(googleRes, 300_000L)); // 5 min TTL
            return googleRes;
        }

        return Map.of("found", false, "provider", "google", "message", "No road route found for specified coordinates");
    }

    private Map<String, Object> getGoogleRoutesV2(double startLat, double startLng, double destLat, double destLng,
            String key) {
        try {
            String url = "https://routes.googleapis.com/directions/v2:computeRoutes";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Goog-Api-Key", key);
            headers.set("X-Goog-FieldMask",
                    "routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline,routes.legs");

            Map<String, Object> originLatLng = Map.of("latitude", startLat, "longitude", startLng);
            Map<String, Object> originLocation = Map.of("latLng", originLatLng);
            Map<String, Object> origin = Map.of("location", originLocation);

            Map<String, Object> destLatLng = Map.of("latitude", destLat, "longitude", destLng);
            Map<String, Object> destLocation = Map.of("latLng", destLatLng);
            Map<String, Object> dest = Map.of("location", destLocation);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("origin", origin);
            requestBody.put("destination", dest);
            requestBody.put("travelMode", "DRIVE");
            requestBody.put("routingPreference", "TRAFFIC_AWARE");

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode routes = root.path("routes");
                if (routes.isArray() && routes.size() > 0) {
                    JsonNode firstRoute = routes.get(0);
                    double distanceMeters = firstRoute.path("distanceMeters").asDouble(0.0);
                    String durationStr = firstRoute.path("duration").asText("");
                    double durationSeconds = 0.0;
                    if (durationStr.endsWith("s")) {
                        try {
                            durationSeconds = Double.parseDouble(durationStr.substring(0, durationStr.length() - 1));
                        } catch (Exception ignored) {
                        }
                    }

                    String encodedPolyline = firstRoute.path("polyline").path("encodedPolyline").asText("");
                    List<List<Double>> leafletCoordinates = decodePolyline(encodedPolyline);
                    List<List<Double>> geoJsonCoordinates = new ArrayList<>();
                    for (List<Double> pt : leafletCoordinates) {
                        geoJsonCoordinates.add(Arrays.asList(pt.get(1), pt.get(0)));
                    }

                    Map<String, Object> result = new HashMap<>();
                    result.put("provider", "google");
                    result.put("found", true);
                    result.put("coordinates", geoJsonCoordinates);
                    result.put("leafletCoordinates", leafletCoordinates);
                    result.put("distanceMeters", Math.round(distanceMeters));
                    result.put("durationSeconds", Math.round(durationSeconds));
                    result.put("distanceText", distanceMeters < 1000
                            ? Math.round(distanceMeters) + " m"
                            : String.format(Locale.US, "%.1f km", distanceMeters / 1000.0));
                    int mins = Math.max(1, (int) Math.round(durationSeconds / 60.0));
                    result.put("durationText", mins < 60
                            ? mins + " min" + (mins == 1 ? "" : "s")
                            : (mins / 60) + "h " + (mins % 60) + "m");
                    result.put("polyline", encodedPolyline);
                    result.put("directions", root);

                    return result;
                }
            }
        } catch (Exception e) {
            logger.warn("Google Routes API v2 compute failed in tracking service: {}", e.getMessage());
        }
        return Collections.emptyMap();
    }

    private Map<String, Object> getGoogleRoadRoute(double startLat, double startLng, double destLat, double destLng,
            String key) {
        try {
            URI uri = UriComponentsBuilder.fromHttpUrl("https://maps.googleapis.com/maps/api/directions/json")
                    .queryParam("origin", startLat + "," + startLng)
                    .queryParam("destination", destLat + "," + destLng)
                    .queryParam("mode", "driving")
                    .queryParam("key", key)
                    .build()
                    .toUri();

            ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                String status = root.path("status").asText("");
                if ("OK".equalsIgnoreCase(status)) {
                    JsonNode routes = root.path("routes");
                    if (routes.isArray() && routes.size() > 0) {
                        JsonNode firstRoute = routes.get(0);
                        JsonNode leg = firstRoute.path("legs").get(0);

                        double distanceMeters = leg.path("distance").path("value").asDouble(0.0);
                        double durationSeconds = leg.path("duration").path("value").asDouble(0.0);
                        String encodedPolyline = firstRoute.path("overview_polyline").path("points").asText("");

                        List<List<Double>> leafletCoordinates = decodePolyline(encodedPolyline);
                        List<List<Double>> geoJsonCoordinates = new ArrayList<>();
                        for (List<Double> pt : leafletCoordinates) {
                            geoJsonCoordinates.add(Arrays.asList(pt.get(1), pt.get(0)));
                        }

                        Map<String, Object> result = new HashMap<>();
                        result.put("provider", "google");
                        result.put("found", true);
                        result.put("coordinates", geoJsonCoordinates);
                        result.put("leafletCoordinates", leafletCoordinates);
                        result.put("distanceMeters", Math.round(distanceMeters));
                        result.put("durationSeconds", Math.round(durationSeconds));
                        result.put("distanceText", leg.path("distance").path("text").asText(distanceMeters < 1000
                                ? Math.round(distanceMeters) + " m"
                                : String.format(Locale.US, "%.1f km", distanceMeters / 1000.0)));
                        int mins = Math.max(1, (int) Math.round(durationSeconds / 60.0));
                        result.put("durationText", leg.path("duration").path("text").asText(mins < 60
                                ? mins + " min" + (mins == 1 ? "" : "s")
                                : (mins / 60) + "h " + (mins % 60) + "m"));
                        result.put("polyline", encodedPolyline);
                        result.put("directions", root);

                        return result;
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Google Directions API failed in tracking service: {}", e.getMessage());
        }
        return Collections.emptyMap();
    }

    private static List<List<Double>> decodePolyline(String encoded) {
        List<List<Double>> poly = new ArrayList<>();
        if (encoded == null || encoded.isBlank()) {
            return poly;
        }

        int index = 0, len = encoded.length();
        int lat = 0, lng = 0;

        while (index < len) {
            int b, shift = 0, result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            int dlat = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            lat += dlat;

            shift = 0;
            result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            int dlng = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            lng += dlng;

            double latitude = (double) lat / 1E5;
            double longitude = (double) lng / 1E5;
            poly.add(Arrays.asList(latitude, longitude));
        }
        return poly;
    }

    public double haversine(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371; // Earth radius in km
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    public long getProcessedEventsCount() {
        return processedEventsCount.get();
    }

    public long getDroppedEventsCount() {
        return droppedEventsCount.get();
    }

    public int getEventQueueSize() {
        return geoSignalQueue.size() + pendingGeoWrites.size();
    }

    public int getActiveSessionsCount() {
        return activeLocationsMap.size();
    }
}
