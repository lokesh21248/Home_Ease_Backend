package com.homeease.tracking.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.tracking.dto.LocationPayload;
import com.homeease.tracking.exception.RateLimitExceededException;
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

    @Value("${homeease.tracking.rate-limit.window-seconds:60}")
    private int windowSeconds;

    @Value("${homeease.tracking.rate-limit.max-updates:30}")
    private int maxUpdates;

    @Value("${homeease.tracking.rate-limit.movement-threshold-meters:15.0}")
    private double movementThresholdMeters;

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
    // DSA 1: In-Memory L1 Hash Table (ConcurrentHashMap) for ultra-low latency O(1) reads
    // =========================================================================
    private final ConcurrentMap<String, LocationPayload> activeLocationsMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> locationLastAccessMap = new ConcurrentHashMap<>();

    // =========================================================================
    // DSA 2: Per-booking monotonic sequence counter (AtomicLong, O(1))
    // Backed by Redis to preserve monotonicity across restarts & multi-node instances.
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
            }
    );

    // =========================================================================
    // DSA 5: Bounded Ring Buffer / Queue with Backpressure (ArrayBlockingQueue)
    // =========================================================================
    public record TrackingTask(String bookingId, LocationPayload payload, String effectiveWorkerId) {}
    private final BlockingQueue<TrackingTask> eventQueue = new ArrayBlockingQueue<>(5000);
    private final AtomicLong droppedEventsCount = new AtomicLong(0L);
    private final AtomicLong processedEventsCount = new AtomicLong(0L);

    // =========================================================================
    // DSA 6: PriorityQueue for Scheduled Retry Processing (Exponential Backoff)
    // =========================================================================
    public record RetryTask(TrackingTask task, long scheduledAtMs, int attempt) implements Comparable<RetryTask> {
        @Override
        public int compareTo(RetryTask o) {
            return Long.compare(this.scheduledAtMs, o.scheduledAtMs);
        }
    }
    private final PriorityQueue<RetryTask> retryQueue = new PriorityQueue<>();

    private final ExecutorService queueExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LiveTracking-EventWorker");
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
            }
    );

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
        queueExecutor.submit(this::processQueuedEvents);
        logger.info("LiveTrackingService initialized with advanced DSA: L1 Map, Atomic Sequences, Bounded Deque, Dedup LRU, Ring Buffer, PriorityQueue Retry, Geohash Index & Redis GEO");
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
        logger.info("LiveTrackingService gracefully shut down event buffer");
    }

    /**
     * Background consumer thread for asynchronous Redis persistence, Geospatial indexing,
     * and PriorityQueue retry processing.
     */
    private void processQueuedEvents() {
        while (running) {
            try {
                // 1. Process any pending scheduled retries whose backoff has expired
                processPendingRetries();

                // 2. Poll event ring buffer
                TrackingTask task = eventQueue.poll(200, TimeUnit.MILLISECONDS);
                if (task != null) {
                    boolean success = persistToRedisAndGeo(task);
                    if (!success) {
                        scheduleRetry(task, 1);
                    } else {
                        processedEventsCount.incrementAndGet();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.warn("Unexpected error in tracking background worker: {}", e.getMessage());
            }
        }
    }

    private void scheduleRetry(TrackingTask task, int attempt) {
        if (attempt > 3) {
            logger.error("Redis persistence dropped after 3 attempts for booking {}", task.bookingId());
            return;
        }
        long backoffMs = (long) (1000 * Math.pow(2, attempt - 1));
        long scheduledAt = System.currentTimeMillis() + backoffMs;
        synchronized (retryQueue) {
            if (retryQueue.size() < 1000) {
                retryQueue.offer(new RetryTask(task, scheduledAt, attempt));
                logger.debug("Scheduled Redis persistence retry attempt {} for booking {} in {}ms", attempt, task.bookingId(), backoffMs);
            } else {
                logger.warn("Retry queue full, discarding retry for booking {}", task.bookingId());
            }
        }
    }

    private void processPendingRetries() {
        long now = System.currentTimeMillis();
        synchronized (retryQueue) {
            while (!retryQueue.isEmpty() && retryQueue.peek().scheduledAtMs() <= now) {
                RetryTask retry = retryQueue.poll();
                boolean success = persistToRedisAndGeo(retry.task());
                if (!success) {
                    scheduleRetry(retry.task(), retry.attempt() + 1);
                } else {
                    processedEventsCount.incrementAndGet();
                }
            }
        }
    }

    private boolean persistToRedisAndGeo(TrackingTask task) {
        try {
            String json = objectMapper.writeValueAsString(task.payload());
            String trackingKey = "tracking:booking:" + task.bookingId();
            redisTemplate.opsForValue().set(trackingKey, json, TRACKING_TTL);

            // Synchronize distributed sequence counter in Redis
            if (task.payload().getSequenceNumber() != null) {
                redisTemplate.opsForValue().set("tracking:seq:" + task.bookingId(),
                        String.valueOf(task.payload().getSequenceNumber()), TRACKING_TTL);
            }

            if (task.payload().getLat() != null && task.payload().getLng() != null) {
                redisTemplate.opsForGeo().add(GEO_WORKERS_KEY,
                        new Point(task.payload().getLng(), task.payload().getLat()),
                        task.effectiveWorkerId());
            }
            return true;
        } catch (Exception e) {
            logger.warn("Async Redis persistence failed for booking {}: {}", task.bookingId(), e.getMessage());
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
    public LocationPayload processLocationUpdateDirect(String bookingId, LocationPayload payload, String authenticatedUserId) {
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
     * 5. Assignment & booking state in Redis (rejects if completed/cancelled or unauthorized)
     * 6. Sliding-window rate limit (throttles spam while permitting legitimate movement)
     * 7. Timestamp ordering (rejects stale updates, clamps future timestamps)
     * 8. Stationary duplicate suppression (avoids redundant broadcasts when stopped)
     * 9. Geohash spatial bucketing & Monotonic atomic sequence generation
     * 10. Bounded breadcrumb history deque
     */
    public LocationPayload processLocationUpdate(String bookingId, LocationPayload payload, String authenticatedUserId) {
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

        // 1b. Validate GPS accuracy – reject fixes with excessive positional uncertainty
        if (payload.getAccuracy() != null && payload.getAccuracy() > maxAccuracyMeters) {
            logger.warn("[tracking] Rejected poor-accuracy GPS fix for booking {}: accuracy={}m > threshold={}m",
                    bookingId, payload.getAccuracy(), maxAccuracyMeters);
            throw new IllegalArgumentException(String.format(
                    "GPS accuracy %.1fm exceeds maximum allowed %.1fm — fix rejected",
                    payload.getAccuracy(), maxAccuracyMeters));
        }

        // 1c. Validate and normalize Bearing (0..360 degrees)
        if (payload.getBearing() != null) {
            double b = payload.getBearing();
            if (Double.isNaN(b) || Double.isInfinite(b)) {
                payload.setBearing(0.0);
            } else {
                b = b % 360.0;
                if (b < 0.0) b += 360.0;
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

        // 2. Validate booking assignment & status in Redis if assignment metadata exists
        String assignmentKey = "tracking:assignment:" + bookingId;
        Double customerLat = null;
        Double customerLng = null;
        try {
            String assignmentJson = redisTemplate.opsForValue().get(assignmentKey);
            if (assignmentJson != null && !assignmentJson.isBlank()) {
                JsonNode assignmentNode = objectMapper.readTree(assignmentJson);
                String bookingStatus = assignmentNode.path("status").asText("");
                if ("COMPLETED".equalsIgnoreCase(bookingStatus) || "CANCELLED".equalsIgnoreCase(bookingStatus)) {
                    // Evict from active locations & history
                    activeLocationsMap.remove(bookingId);
                    locationLastAccessMap.remove(bookingId);
                    locationHistoryMap.remove(bookingId);
                    sequenceCounters.remove(bookingId);
                    throw new IllegalStateException("Live tracking has ended: booking " + bookingId + " is " + bookingStatus);
                }

                // If authenticated worker provided, verify against assigned worker
                if (authenticatedUserId != null && !authenticatedUserId.isBlank()) {
                    String assignedUserId = assignmentNode.path("userId").asText("");
                    String assignedWorkerId = assignmentNode.path("workerId").asText("");
                    if (!assignedUserId.isBlank() && !assignedUserId.equalsIgnoreCase(authenticatedUserId) &&
                            !assignedWorkerId.isBlank() && !assignedWorkerId.equalsIgnoreCase(authenticatedUserId)) {
                        throw new SecurityException("Authenticated user " + authenticatedUserId + " is not assigned to booking " + bookingId);
                    }
                }

                if (assignmentNode.has("customerLat") && assignmentNode.has("customerLng")) {
                    customerLat = assignmentNode.path("customerLat").asDouble();
                    customerLng = assignmentNode.path("customerLng").asDouble();
                }
            }
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            throw e;
        } catch (Exception e) {
            logger.warn("Could not check assignment cache for booking {}: {}", bookingId, e.getMessage());
        }

        // 3. Stale update protection & Monotonic Timestamp Clamping
        long now = System.currentTimeMillis();
        long clientTimestamp = payload.getTimestamp() != null ? payload.getTimestamp() : now;
        long effectiveTimestamp = Math.min(clientTimestamp, now + 5000); // Clamp future timestamps
        payload.setTimestamp(effectiveTimestamp);

        // 3b. DSA 4: Bounded Expiring Deduplication Check (Exact packet retransmissions)
        String dedupFingerprint = String.format(Locale.US, "%s:%.6f:%.6f:%d", bookingId, lat, lng, effectiveTimestamp);
        Long prevSeen = deduplicationCache.put(dedupFingerprint, now);
        if (prevSeen != null && (now - prevSeen) < 60_000) {
            logger.debug("[tracking] Duplicate GPS packet dropped by dedup cache for booking {}", bookingId);
            LocationPayload cached = activeLocationsMap.get(bookingId);
            return cached != null ? cached : payload;
        }

        LocationPayload latestExisting = getLatestLocation(bookingId);
        if (latestExisting != null && latestExisting.getTimestamp() != null) {
            if (effectiveTimestamp <= latestExisting.getTimestamp()) {
                logger.warn("[tracking] Stale GPS rejected for booking {}: incomingTs={} <= cachedTs={}",
                        bookingId, effectiveTimestamp, latestExisting.getTimestamp());
                return latestExisting;
            }

            // 3c. Teleportation Anomaly Filter
            long dtMs = effectiveTimestamp - latestExisting.getTimestamp();
            if (dtMs > 0 && dtMs < 300_000) { // within last 5 minutes
                double distanceMeters = haversine(latestExisting.getLat(), latestExisting.getLng(), lat, lng) * 1000.0;
                double speedMps = distanceMeters / (dtMs / 1000.0);
                if (distanceMeters > 300.0 && speedMps > 55.0) { // > 200 km/h impossible vehicle speed
                    logger.warn("[tracking] Teleportation anomaly rejected for booking {}: moved {}m in {}ms ({} km/h)",
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
                logger.debug("[tracking] Stationary duplicate suppressed for booking {} (moved={}m, <{}m threshold)",
                        bookingId, String.format("%.2f", movedDistMeters), STATIONARY_NOISE_THRESHOLD_METERS);
                return latestExisting;
            }
        }

        // 4. Sliding-Window Rate Limiting (DSA 8)
        String effectiveWorkerId = (authenticatedUserId != null && !authenticatedUserId.isBlank())
                ? authenticatedUserId
                : payload.getEffectiveId();
        String rateLimitKey = effectiveWorkerId != null ? effectiveWorkerId : bookingId;
        checkSlidingWindowRateLimit(rateLimitKey, lat, lng, latestExisting);

        // 5. Populate real-time distance and ETA if customer coordinates are known
        if (customerLat != null && customerLng != null) {
            double distanceKm = haversine(lat, lng, customerLat, customerLng);
            int etaMinutes = Math.max(1, (int) Math.round((distanceKm / 25.0) * 60.0) + 2);
            payload.setDistanceKm(Math.round(distanceKm * 100.0) / 100.0);
            payload.setEtaMinutes(etaMinutes);
        }

        payload.setTrackingActive(true);
        payload.setStatus("TRACKING");

        // 5b. DSA 7: Compute Geohash Spatial Key (~153m street block precision)
        String geohash = GeohashUtils.encode(lat, lng, 7);
        payload.setGeohash(geohash);
        spatialGeohashIndex.computeIfAbsent(geohash, k -> ConcurrentHashMap.newKeySet()).add(bookingId);
        if (latestExisting != null && latestExisting.getGeohash() != null && !latestExisting.getGeohash().equals(geohash)) {
            Set<String> oldBucket = spatialGeohashIndex.get(latestExisting.getGeohash());
            if (oldBucket != null) {
                oldBucket.remove(bookingId);
                if (oldBucket.isEmpty()) {
                    spatialGeohashIndex.remove(latestExisting.getGeohash());
                }
            }
        }

        // 5c. DSA 2: Assign monotonic sequence number (AtomicLong synchronized from Redis)
        AtomicLong counter = sequenceCounters.computeIfAbsent(bookingId, k -> {
            long initial = 0L;
            try {
                String redisSeq = redisTemplate.opsForValue().get("tracking:seq:" + k);
                if (redisSeq != null && !redisSeq.isBlank()) {
                    initial = Long.parseLong(redisSeq.trim());
                }
            } catch (Exception ignored) {}
            return new AtomicLong(initial);
        });
        long seq = counter.incrementAndGet();
        payload.setSequenceNumber(seq);

        // 6. DSA 1: In-Memory L1 Cache update (O(1) Instant Access)
        activeLocationsMap.put(bookingId, payload);
        locationLastAccessMap.put(bookingId, now);

        // 6b. DSA 3: Bounded GPS history deque (Breadcrumb trail, max 20 fixes)
        Deque<LocationPayload> history = locationHistoryMap.computeIfAbsent(bookingId, k -> new ConcurrentLinkedDeque<>());
        history.addLast(payload);
        while (history.size() > MAX_HISTORY_ENTRIES) {
            history.pollFirst();
        }

        // 6c. Memory Safeguard: prune L1 cache if exceeding capacity
        if (activeLocationsMap.size() > MAX_L1_CAPACITY) {
            pruneStaleL1Entries(now);
        }

        // 7. Push to WebSocket Topics immediately: /topic/tracking/{bookingId} & /topic/track/{bookingId}
        long broadcastStartMs = System.currentTimeMillis();
        try {
            messagingTemplate.convertAndSend("/topic/tracking/" + bookingId, payload);
            messagingTemplate.convertAndSend("/topic/track/" + bookingId, payload);
            long broadcastLatencyMs = System.currentTimeMillis() - broadcastStartMs;
            long e2eLatencyMs = System.currentTimeMillis() - receiptMs;
            logger.debug("[tracking] Broadcast seq={} for booking {} to /topic/tracking/{}: [{}, {}] bearing={} geohash={} "
                            + "broadcastLatency={}ms e2eLatency={}ms",
                    seq, bookingId, bookingId, payload.getLat(), payload.getLng(), payload.getBearing(),
                    geohash, broadcastLatencyMs, e2eLatencyMs);
        } catch (Exception e) {
            logger.warn("[tracking] WebSocket broadcast failed for booking {}: {}", bookingId, e.getMessage());
        }

        // 8. DSA 5: Bounded Ring Buffer Event Queue for Redis & GEO persistence
        boolean queued = eventQueue.offer(new TrackingTask(bookingId, payload, effectiveWorkerId));
        if (!queued) {
            droppedEventsCount.incrementAndGet();
            logger.warn("Tracking event queue capacity reached (5000), applying backpressure drop for background write on booking {}", bookingId);
        }

        return payload;
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
        if (previous != null && previous.getLat() != null && previous.getLng() != null) {
            double distanceMovedMeters = haversine(previous.getLat(), previous.getLng(), lat, lng) * 1000.0;
            if (distanceMovedMeters >= movementThresholdMeters) {
                // Legitimate vehicle/rider movement: exempt from rate limiting
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
     * Uses in-memory L1 cache (O(1)) with Redis L2 fallback.
     */
    public LocationPayload getLatestLocation(String bookingId) {
        if (bookingId == null || bookingId.isBlank()) {
            return null;
        }

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

        // 2. Check Redis L2 storage
        try {
            String trackingKey = "tracking:booking:" + bookingId;
            String raw = redisTemplate.opsForValue().get(trackingKey);
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

            // Check if booking was marked completed or cancelled
            String assignmentKey = "tracking:assignment:" + bookingId;
            String assignmentJson = redisTemplate.opsForValue().get(assignmentKey);
            if (assignmentJson != null && !assignmentJson.isBlank()) {
                JsonNode assignmentNode = objectMapper.readTree(assignmentJson);
                String status = assignmentNode.path("status").asText("");
                if ("COMPLETED".equalsIgnoreCase(status) || "CANCELLED".equalsIgnoreCase(status)) {
                    LocationPayload closed = LocationPayload.builder()
                            .bookingId(bookingId)
                            .trackingActive(false)
                            .status(status)
                            .message("Tracking ended: booking status is " + status)
                            .build();
                    activeLocationsMap.put(bookingId, closed);
                    return closed;
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

            GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults = redisTemplate.opsForGeo().radius(GEO_WORKERS_KEY, circle, args);
            if (geoResults != null) {
                for (var geoResult : geoResults) {
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
     * Calculates distance (Haversine formula in KM) and estimated time of arrival (ETA in minutes).
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

        return response;
    }

    /**
     * Computes driving route using Google Maps with LRU Cache (DSA 9).
     * Quantizes coordinates to ~100m grid bucket to avoid repeated API requests for slight movements.
     */
    public Map<String, Object> getDrivingRoute(double startLat, double startLng, double destLat, double destLng) {
        if (Double.isNaN(startLat) || Double.isNaN(startLng) || Double.isNaN(destLat) || Double.isNaN(destLng) ||
                startLat < -90.0 || startLat > 90.0 || destLat < -90.0 || destLat > 90.0 ||
                startLng < -180.0 || startLng > 180.0 || destLng < -180.0 || destLng > 180.0) {
            return Map.of(
                    "found", false,
                    "provider", "google",
                    "message", "Invalid coordinate bounds provided for routing"
            );
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

    private Map<String, Object> getGoogleRoutesV2(double startLat, double startLng, double destLat, double destLng, String key) {
        try {
            String url = "https://routes.googleapis.com/directions/v2:computeRoutes";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Goog-Api-Key", key);
            headers.set("X-Goog-FieldMask", "routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline,routes.legs");

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

    private Map<String, Object> getGoogleRoadRoute(double startLat, double startLng, double destLat, double destLng, String key) {
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
        return eventQueue.size();
    }

    public int getActiveSessionsCount() {
        return activeLocationsMap.size();
    }
}
