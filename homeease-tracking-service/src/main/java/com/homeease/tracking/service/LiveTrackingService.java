package com.homeease.tracking.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.tracking.dto.LocationPayload;
import com.homeease.tracking.exception.RateLimitExceededException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.geo.Point;
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
     * Raised from 0.2m to 1.5m to correctly handle GPS horizontal noise.
     */
    private static final double STATIONARY_NOISE_THRESHOLD_METERS = 1.5;

    // Cache TTL: 2 hours for in-transit tracking data
    private static final Duration TRACKING_TTL = Duration.ofHours(2);
    private static final String GEO_WORKERS_KEY = "active_workers_geo";

    // =========================================================================
    // DSA A: In-Memory L1 Hash Table (ConcurrentHashMap) for ultra-low latency O(1) reads
    // =========================================================================
    private final ConcurrentMap<String, LocationPayload> activeLocationsMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> locationLastAccessMap = new ConcurrentHashMap<>();

    // =========================================================================
    // DSA C: Per-booking monotonic sequence counter (AtomicLong, O(1)).
    // Server-assigned: immune to device-clock skew. Clients discard frames
    // with sequenceNumber <= lastSeen to prevent out-of-order rendering.
    // =========================================================================
    private final ConcurrentMap<String, java.util.concurrent.atomic.AtomicLong> sequenceCounters = new ConcurrentHashMap<>();

    // =========================================================================
    // DSA B: Bounded Asynchronous Event Buffer / Queue
    // =========================================================================
    public record TrackingTask(String bookingId, LocationPayload payload, String effectiveWorkerId) {}
    private final BlockingQueue<TrackingTask> eventQueue = new ArrayBlockingQueue<>(5000);
    private final ExecutorService queueExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LiveTracking-EventWorker");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = true;

    // =========================================================================
    // DSA D: Sliding-Window Rate Limiter (Circular timestamp deque per partner)
    // =========================================================================
    private final ConcurrentMap<String, Deque<Long>> partnerRateLimitWindows = new ConcurrentHashMap<>();

    // =========================================================================
    // DSA F: Graph Routing LRU Cache (Spatial Quantization)
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
        logger.info("LiveTrackingService initialized with DSA optimizations: L1 HashMap, Async Event Buffer, Sliding-Window ({}s, max {}), and Route LRU Cache",
                windowSeconds, maxUpdates);
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
     * Background consumer thread for asynchronous Redis persistence & Geospatial indexing.
     */
    private void processQueuedEvents() {
        while (running) {
            try {
                TrackingTask task = eventQueue.poll(500, TimeUnit.MILLISECONDS);
                if (task != null) {
                    persistToRedisAndGeo(task);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.warn("Unexpected error in tracking background worker: {}", e.getMessage());
            }
        }
    }

    private void persistToRedisAndGeo(TrackingTask task) {
        try {
            String json = objectMapper.writeValueAsString(task.payload());
            String trackingKey = "tracking:booking:" + task.bookingId();
            redisTemplate.opsForValue().set(trackingKey, json, TRACKING_TTL);

            if (task.payload().getLat() != null && task.payload().getLng() != null) {
                redisTemplate.opsForGeo().add(GEO_WORKERS_KEY,
                        new Point(task.payload().getLng(), task.payload().getLat()),
                        task.effectiveWorkerId());
            }
        } catch (Exception e) {
            logger.warn("Async Redis persistence failed for booking {}: {}", task.bookingId(), e.getMessage());
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
     * Records partner live coordinates in L1 Cache & Redis (Key-Value + Redis GEO)
     * and broadcasts live to customer WebSocket subscribers.
     *
     * Validates:
     * 1. Coordinate ranges (-90 to +90 lat, -180 to +180 lng, non-NaN)
     * 2. Assignment & booking state in Redis (rejects if completed/cancelled or unauthorized)
     * 3. Sliding-window rate limit (throttles spam while permitting legitimate movement)
     * 4. Timestamp ordering (rejects stale updates, clamps future timestamps)
     * 5. Stationary duplicate suppression (avoids redundant broadcasts when stopped)
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
                    // Evict from active locations
                    activeLocationsMap.remove(bookingId);
                    locationLastAccessMap.remove(bookingId);
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

        // 3. Stale update protection & Monotonic Timestamp Clamping (DSA E)
        long now = System.currentTimeMillis();
        long clientTimestamp = payload.getTimestamp() != null ? payload.getTimestamp() : now;
        long effectiveTimestamp = Math.min(clientTimestamp, now + 5000); // Clamp future timestamps
        payload.setTimestamp(effectiveTimestamp);

        LocationPayload latestExisting = getLatestLocation(bookingId);
        if (latestExisting != null && latestExisting.getTimestamp() != null) {
            if (effectiveTimestamp <= latestExisting.getTimestamp()) {
                logger.warn("[tracking] Stale GPS rejected for booking {}: incomingTs={} <= cachedTs={}",
                        bookingId, effectiveTimestamp, latestExisting.getTimestamp());
                return latestExisting;
            }

            // Stationary noise suppression: threshold raised to 1.5m to handle GPS noise correctly.
            // Suppress only when: moved < STATIONARY_NOISE_THRESHOLD_METERS AND bearing unchanged AND arrived < 3s after previous.
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

        // 4. Sliding-Window Rate Limiting (DSA D)
        String effectiveWorkerId = (authenticatedUserId != null && !authenticatedUserId.isBlank())
                ? authenticatedUserId
                : payload.getEffectiveId();
        String rateLimitKey = effectiveWorkerId != null ? effectiveWorkerId : bookingId;
        checkSlidingWindowRateLimit(rateLimitKey, lat, lng, latestExisting);

        // 5. Populate real-time distance and ETA if customer coordinates are known (DSA F)
        if (customerLat != null && customerLng != null) {
            double distanceKm = haversine(lat, lng, customerLat, customerLng);
            int etaMinutes = Math.max(1, (int) Math.round((distanceKm / 25.0) * 60.0) + 2);
            payload.setDistanceKm(Math.round(distanceKm * 100.0) / 100.0);
            payload.setEtaMinutes(etaMinutes);
        }

        payload.setTrackingActive(true);
        payload.setStatus("TRACKING");

        // 5b. Assign server-side monotonic sequence number (DSA C).
        //     AtomicLong guarantees thread-safety without a lock on the hot path.
        long seq = sequenceCounters
                .computeIfAbsent(bookingId, k -> new java.util.concurrent.atomic.AtomicLong(0L))
                .incrementAndGet();
        payload.setSequenceNumber(seq);

        // 6. In-Memory L1 Cache update (DSA A: O(1) Instant Access)
        activeLocationsMap.put(bookingId, payload);
        locationLastAccessMap.put(bookingId, now);

        // 7. Push to WebSocket Topics immediately: /topic/tracking/{bookingId} & /topic/track/{bookingId}
        long broadcastStartMs = System.currentTimeMillis();
        try {
            messagingTemplate.convertAndSend("/topic/tracking/" + bookingId, payload);
            messagingTemplate.convertAndSend("/topic/track/" + bookingId, payload);
            long broadcastLatencyMs = System.currentTimeMillis() - broadcastStartMs;
            long e2eLatencyMs = System.currentTimeMillis() - receiptMs;
            logger.debug("[tracking] Broadcast seq={} for booking {} to /topic/tracking/{}: [{}, {}] bearing={} "
                            + "broadcastLatency={}ms e2eLatency={}ms",
                    seq, bookingId, bookingId, payload.getLat(), payload.getLng(), payload.getBearing(),
                    broadcastLatencyMs, e2eLatencyMs);
        } catch (Exception e) {
            logger.warn("[tracking] WebSocket broadcast failed for booking {}: {}", bookingId, e.getMessage());
        }

        // 8. Bounded Asynchronous Event Buffer: Queue Redis & GEO persistence (DSA B)
        boolean queued = eventQueue.offer(new TrackingTask(bookingId, payload, effectiveWorkerId));
        if (!queued) {
            logger.warn("Tracking event queue capacity reached (5000), applying backpressure drop for background write on booking {}", bookingId);
        }

        return payload;
    }

    /**
     * Sliding-Window Rate Limiter (DSA D)
     * Throttles if updates > maxUpdates in windowSeconds, unless partner moved >= movementThresholdMeters.
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

        // 1. Check in-memory L1 cache (DSA A)
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
                    // Legacy fallback format "lat,lng"
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
        // Estimate average urban speed: 25 km/h -> 2.4 min per km + 2 min arrival buffer
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
     * Computes driving route using Google Maps with LRU Cache (DSA F).
     * Quantizes coordinates to ~100m grid bucket to avoid repeated API requests for slight movements.
     */
    public Map<String, Object> getDrivingRoute(double startLat, double startLng, double destLat, double destLng) {
        // Validate coordinates
        if (Double.isNaN(startLat) || Double.isNaN(startLng) || Double.isNaN(destLat) || Double.isNaN(destLng) ||
                startLat < -90.0 || startLat > 90.0 || destLat < -90.0 || destLat > 90.0 ||
                startLng < -180.0 || startLng > 180.0 || destLng < -180.0 || destLng > 180.0) {
            return Map.of(
                    "found", false,
                    "provider", "google",
                    "message", "Invalid coordinate bounds provided for routing"
            );
        }

        // Check LRU Route Cache (Quantized to 3 decimal places ~110m bucket)
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

        // 1. Try modern Google Routes API v2
        Map<String, Object> routesV2 = getGoogleRoutesV2(startLat, startLng, destLat, destLng, googleKey);
        if (routesV2 != null && Boolean.TRUE.equals(routesV2.get("found"))) {
            routeCache.put(cacheKey, new RouteCacheEntry(routesV2, 300_000L)); // 5 min TTL
            return routesV2;
        }

        // 2. Try legacy Google Directions API
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
                    String durationStr = firstRoute.path("duration").asText(""); // e.g. "342s"
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
                        geoJsonCoordinates.add(Arrays.asList(pt.get(1), pt.get(0))); // [lng, lat]
                    }

                    Map<String, Object> result = new HashMap<>();
                    result.put("provider", "google");
                    result.put("found", true);
                    result.put("coordinates", geoJsonCoordinates);       // [lng, lat]
                    result.put("leafletCoordinates", leafletCoordinates); // [lat, lng]
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

                    logger.info("Google Routes API v2 route computed in tracking service: {} m, {} s, {} points",
                            Math.round(distanceMeters), Math.round(durationSeconds), geoJsonCoordinates.size());
                    return result;
                }
            }
        } catch (Exception e) {
            logger.warn("Google Routes API v2 compute failed in tracking service, trying fallback: {}", e.getMessage());
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
                            geoJsonCoordinates.add(Arrays.asList(pt.get(1), pt.get(0))); // [lng, lat]
                        }

                        Map<String, Object> result = new HashMap<>();
                        result.put("provider", "google");
                        result.put("found", true);
                        result.put("coordinates", geoJsonCoordinates);       // [lng, lat]
                        result.put("leafletCoordinates", leafletCoordinates); // [lat, lng]
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

                        logger.info("Google Directions route computed: {} m, {} s, {} points",
                                Math.round(distanceMeters), Math.round(durationSeconds), geoJsonCoordinates.size());
                        return result;
                    }
                } else {
                    logger.warn("Google Directions API returned status in tracking service: {}", status);
                }
            }
        } catch (Exception e) {
            logger.warn("Google Directions API failed in tracking service, trying fallback: {}", e.getMessage());
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
}
