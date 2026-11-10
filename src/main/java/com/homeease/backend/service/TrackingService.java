package com.homeease.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.dto.TrackingDto.*;
import com.homeease.backend.dto.WorkerDto.LocationUpdateRequest;
import com.homeease.backend.exception.ResourceNotFoundException;
import com.homeease.backend.model.entity.Booking;
import com.homeease.backend.model.entity.Worker;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.TrackingSessionStatus;
import com.homeease.backend.repository.BookingRepository;
import com.homeease.backend.repository.WorkerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TrackingService {

    private static final Logger logger = LoggerFactory.getLogger(TrackingService.class);

    public static final String REDIS_SESSION_PREFIX = "tracking:session:";
    public static final String REDIS_BOOKING_PREFIX = "tracking:booking:";
    public static final String REDIS_ASSIGNMENT_PREFIX = "tracking:assignment:";

    private static final Duration SESSION_TTL = Duration.ofHours(4);
    private static final Duration LIVE_COORD_TTL = Duration.ofHours(2);
    private static final Duration ARRIVED_COORD_TTL = Duration.ofSeconds(120);

    // In-memory throttles to prevent excessive Redis/DB writes on high-frequency GPS ticks
    private final ConcurrentHashMap<UUID, Long> sessionTickCounter = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastWorkerHeartbeatMap = new ConcurrentHashMap<>();

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final BookingRepository bookingRepository;
    private final WorkerRepository workerRepository;
    private final WorkerService workerService;

    @Autowired
    public TrackingService(
            RedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper,
            SimpMessagingTemplate messagingTemplate,
            BookingRepository bookingRepository,
            WorkerRepository workerRepository,
            WorkerService workerService) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.objectMapper.findAndRegisterModules();
        this.messagingTemplate = messagingTemplate;
        this.bookingRepository = bookingRepository;
        this.workerRepository = workerRepository;
        this.workerService = workerService;
    }

    /**
     * Initializes or returns an existing tracking session when a booking is accepted.
     */
    public TrackingSessionDto createTrackingSession(Booking booking) {
        if (booking == null) return null;
        UUID bookingId = booking.getBookingId();
        String sessionKey = REDIS_SESSION_PREFIX + bookingId;

        TrackingSessionDto existing = getSession(bookingId);
        if (existing != null) {
            return existing;
        }

        UUID workerUserId = (booking.getWorker() != null && booking.getWorker().getUser() != null)
                ? booking.getWorker().getUser().getUserId() : null;
        UUID customerUserId = (booking.getUser() != null) ? booking.getUser().getUserId() : null;

        TrackingSessionDto session = TrackingSessionDto.builder()
                .sessionId(UUID.randomUUID().toString())
                .bookingId(bookingId)
                .workerUserId(workerUserId)
                .customerUserId(customerUserId)
                .status(TrackingSessionStatus.CREATED)
                .trackingActive(false)
                .createdAt(Instant.now())
                .build();

        saveSession(session, SESSION_TTL);
        updateAssignmentKey(bookingId, "ACCEPTED", false);

        logger.info("Initialized tracking session {} for booking {}", session.getSessionId(), bookingId);
        return session;
    }

    /**
     * Starts active GPS travel tracking when worker begins travelling towards customer.
     */
    public TrackingSessionDto startTravelling(UUID bookingId, UUID workerUserId) {
        TrackingSessionDto session = getSession(bookingId);
        if (session == null) {
            Booking booking = bookingRepository.findById(bookingId).orElse(null);
            if (booking != null) {
                session = createTrackingSession(booking);
            }
        }

        if (session == null) {
            throw new ResourceNotFoundException("Tracking session not found for booking: " + bookingId);
        }

        validateWorkerOwnership(session, workerUserId);

        session.setStatus(TrackingSessionStatus.ACTIVE);
        session.setTrackingActive(true);
        session.setStartedAt(Instant.now());
        saveSession(session, SESSION_TTL);
        updateAssignmentKey(bookingId, "ON_THE_WAY", true);

        // Broadcast WORKER_ON_THE_WAY event
        TrackingEventMessage event = TrackingEventMessage.builder()
                .type("WORKER_ON_THE_WAY")
                .bookingId(bookingId.toString())
                .status("ON_THE_WAY")
                .trackingStatus(TrackingSessionStatus.ACTIVE.name())
                .trackingActive(true)
                .message("Service partner is travelling to your location.")
                .timestamp(System.currentTimeMillis())
                .build();

        broadcastTrackingEvent(bookingId, session.getCustomerUserId(), event);
        logger.info("Tracking session {} for booking {} is now ACTIVE", session.getSessionId(), bookingId);
        return session;
    }

    /**
     * Ingests high-frequency GPS coordinate update from worker during travel.
     * Rejects updates if session is ARRIVED or STOPPED.
     */
    public LocationUpdateResult updateWorkerLocation(UUID bookingId, UUID workerUserId, LocationUpdateRequest req) {
        TrackingSessionDto session = getSession(bookingId);
        if (session == null) {
            // Lazy-init session if booking is currently in travel stage
            Booking booking = bookingRepository.findById(bookingId).orElse(null);
            if (booking != null && (booking.getStatus() == BookingStage.ON_THE_WAY || booking.getStatus() == BookingStage.ACCEPTED)) {
                session = createTrackingSession(booking);
                session.setStatus(TrackingSessionStatus.ACTIVE);
                session.setTrackingActive(true);
                session.setStartedAt(Instant.now());
                saveSession(session, SESSION_TTL);
            }
        }

        if (session == null) {
            return LocationUpdateResult.builder()
                    .success(false)
                    .status("NOT_FOUND")
                    .trackingActive(false)
                    .message("No tracking session found for booking " + bookingId)
                    .build();
        }

        // CRITICAL: Reject location uploads if tracking session is ARRIVED or STOPPED
        if (session.getStatus() == TrackingSessionStatus.ARRIVED || session.getStatus() == TrackingSessionStatus.STOPPED) {
            logger.warn("Rejected GPS upload for booking {}: tracking session is {}", bookingId, session.getStatus());
            return LocationUpdateResult.builder()
                    .success(false)
                    .status(session.getStatus().name())
                    .trackingActive(false)
                    .message("Tracking session is " + session.getStatus() + ". GPS uploads rejected.")
                    .build();
        }

        // Validate worker ownership
        validateWorkerOwnership(session, workerUserId);

        // Stale timestamp validation (drop older than 2 minutes)
        long nowMs = System.currentTimeMillis();
        long coordTime = (req.getTimestamp() != null && req.getTimestamp() > 0) ? req.getTimestamp() : nowMs;
        if (Math.abs(nowMs - coordTime) > 120_000) {
            logger.debug("Dropped stale GPS frame (age {} ms) for booking {}", Math.abs(nowMs - coordTime), bookingId);
            return LocationUpdateResult.builder()
                    .success(true)
                    .status(session.getStatus().name())
                    .trackingActive(true)
                    .message("Stale coordinate ignored")
                    .build();
        }

        // 1. Update session coordinates in-memory; persist session metadata to Redis periodically (every 10 ticks)
        session.setLastLat(req.getLat());
        session.setLastLng(req.getLng());
        if (req.getBearing() != null) session.setBearing(req.getBearing());
        if (req.getSpeed() != null) session.setSpeed(req.getSpeed());
        if (req.getAccuracy() != null) session.setAccuracy(req.getAccuracy());
        session.setLastTimestamp(coordTime);

        long ticks = sessionTickCounter.compute(bookingId, (k, v) -> v == null ? 1L : v + 1L);
        if (ticks % 10 == 0) {
            saveSession(session, SESSION_TTL);
        }

        // 2. Cache latest live coordinate in Redis (fast key update for live customer polling)
        Map<String, Object> coordMap = new LinkedHashMap<>();
        coordMap.put("bookingId", bookingId.toString());
        coordMap.put("lat", req.getLat());
        coordMap.put("lng", req.getLng());
        coordMap.put("latitude", req.getLat());
        coordMap.put("longitude", req.getLng());
        coordMap.put("bearing", req.getBearing() != null ? req.getBearing() : 0.0);
        coordMap.put("heading", req.getBearing() != null ? req.getBearing() : 0.0);
        coordMap.put("speed", req.getSpeed() != null ? req.getSpeed() : 0.0);
        coordMap.put("accuracy", req.getAccuracy() != null ? req.getAccuracy() : 5.0);
        coordMap.put("timestamp", coordTime);
        coordMap.put("trackingActive", true);
        coordMap.put("status", "ACTIVE");

        try {
            redisTemplate.opsForValue().set(
                    REDIS_BOOKING_PREFIX + bookingId,
                    objectMapper.writeValueAsString(coordMap),
                    LIVE_COORD_TTL
            );
        } catch (Exception e) {
            logger.warn("Failed to cache live coordinate in Redis for booking {}: {}", bookingId, e.getMessage());
        }

        // 3. Broadcast WORKER_LOCATION_UPDATED via WebSockets to customer app
        TrackingEventMessage event = TrackingEventMessage.builder()
                .type("WORKER_LOCATION_UPDATED")
                .bookingId(bookingId.toString())
                .status("ON_THE_WAY")
                .trackingStatus(TrackingSessionStatus.ACTIVE.name())
                .trackingActive(true)
                .lat(req.getLat())
                .lng(req.getLng())
                .bearing(req.getBearing() != null ? req.getBearing() : 0.0)
                .speed(req.getSpeed() != null ? req.getSpeed() : 0.0)
                .accuracy(req.getAccuracy() != null ? req.getAccuracy() : 5.0)
                .timestamp(coordTime)
                .build();

        broadcastTrackingEvent(bookingId, session.getCustomerUserId(), event);

        // 4. Update worker general availability heartbeat (throttled to at most once per 15s)
        try {
            if (workerUserId != null) {
                Long lastHb = lastWorkerHeartbeatMap.get(workerUserId);
                if (lastHb == null || (nowMs - lastHb) >= 15_000) {
                    lastWorkerHeartbeatMap.put(workerUserId, nowMs);
                    workerService.updateLiveLocation(workerUserId, req.getLat(), req.getLng());
                }
            }
        } catch (Exception ignored) {}

        return LocationUpdateResult.builder()
                .success(true)
                .status("ACTIVE")
                .trackingActive(true)
                .message("Location updated successfully")
                .build();
    }

    /**
     * Marks the tracking session as ARRIVED when worker arrives at doorstep.
     * Transitions session to ARRIVED and broadcasts WORKER_ARRIVED & BOOKING_TRACKING_STOPPED.
     * Preserves worker general availability.
     */
    public TrackingSessionDto handleArrival(UUID bookingId, UUID workerUserId) {
        TrackingSessionDto session = getSession(bookingId);
        if (session == null) {
            Booking booking = bookingRepository.findById(bookingId).orElse(null);
            if (booking != null) {
                session = createTrackingSession(booking);
            }
        }

        if (session != null && workerUserId != null) {
            validateWorkerOwnership(session, workerUserId);
        }

        if (session == null) {
            session = TrackingSessionDto.builder()
                    .sessionId(UUID.randomUUID().toString())
                    .bookingId(bookingId)
                    .workerUserId(workerUserId)
                    .build();
        }

        Instant arrivedAt = Instant.now();
        session.setStatus(TrackingSessionStatus.ARRIVED);
        session.setTrackingActive(false);
        session.setArrivedAt(arrivedAt);
        saveSession(session, SESSION_TTL);
        sessionTickCounter.remove(bookingId);

        // Update assignment key
        updateAssignmentKey(bookingId, "ARRIVED", false);

        // Set short TTL on live coordinate key so it cleanly expires
        try {
            String coordKey = REDIS_BOOKING_PREFIX + bookingId;
            String coordJson = redisTemplate.opsForValue().get(coordKey);
            if (coordJson != null && !coordJson.isBlank()) {
                Map<String, Object> map = objectMapper.readValue(coordJson, new TypeReference<>() {});
                map.put("trackingActive", false);
                map.put("status", "ARRIVED");
                redisTemplate.opsForValue().set(coordKey, objectMapper.writeValueAsString(map), ARRIVED_COORD_TTL);
            }
        } catch (Exception ignored) {}

        // 1. Broadcast WORKER_ARRIVED event
        TrackingEventMessage arrivedEvent = TrackingEventMessage.builder()
                .type("WORKER_ARRIVED")
                .bookingId(bookingId.toString())
                .status("ARRIVED")
                .trackingStatus(TrackingSessionStatus.ARRIVED.name())
                .trackingActive(false)
                .message("Your service partner has arrived at your location.")
                .timestamp(System.currentTimeMillis())
                .build();

        broadcastTrackingEvent(bookingId, session.getCustomerUserId(), arrivedEvent);

        // 2. Broadcast BOOKING_TRACKING_STOPPED event
        TrackingEventMessage stoppedEvent = TrackingEventMessage.builder()
                .type("BOOKING_TRACKING_STOPPED")
                .bookingId(bookingId.toString())
                .status("ARRIVED")
                .trackingStatus(TrackingSessionStatus.ARRIVED.name())
                .trackingActive(false)
                .reason("WORKER_ARRIVED")
                .message("Live travel tracking has concluded upon partner arrival.")
                .timestamp(System.currentTimeMillis())
                .build();

        broadcastTrackingEvent(bookingId, session.getCustomerUserId(), stoppedEvent);

        logger.info("Tracking session for booking {} stopped upon ARRIVAL", bookingId);
        return session;
    }

    /**
     * Stops the tracking session permanently when booking is COMPLETED or CANCELLED.
     * Deletes temporary Redis live coordinates while preserving DB records and worker availability.
     */
    public TrackingSessionDto stopTracking(UUID bookingId, String reason) {
        TrackingSessionDto session = getSession(bookingId);
        if (session == null) {
            session = TrackingSessionDto.builder()
                    .sessionId(UUID.randomUUID().toString())
                    .bookingId(bookingId)
                    .build();
        }

        session.setStatus(TrackingSessionStatus.STOPPED);
        session.setTrackingActive(false);
        session.setStoppedAt(Instant.now());
        session.setStopReason(reason);
        saveSession(session, Duration.ofMinutes(10)); // Short audit retention
        sessionTickCounter.remove(bookingId);

        // Update assignment key
        updateAssignmentKey(bookingId, reason, false);

        // Delete temporary live GPS coordinates
        try {
            redisTemplate.delete(REDIS_BOOKING_PREFIX + bookingId);
        } catch (Exception ignored) {}

        // Broadcast BOOKING_TRACKING_STOPPED
        TrackingEventMessage stoppedEvent = TrackingEventMessage.builder()
                .type("BOOKING_TRACKING_STOPPED")
                .bookingId(bookingId.toString())
                .status(reason)
                .trackingStatus(TrackingSessionStatus.STOPPED.name())
                .trackingActive(false)
                .reason(reason)
                .message("Live tracking ended: " + reason)
                .timestamp(System.currentTimeMillis())
                .build();

        broadcastTrackingEvent(bookingId, session.getCustomerUserId(), stoppedEvent);

        logger.info("Tracking session for booking {} permanently STOPPED (reason: {})", bookingId, reason);
        return session;
    }

    /**
     * Returns the latest location for customer or worker.
     * Returns trackingActive = false if session is ARRIVED or STOPPED.
     */
    public TrackingLocationResponse getLatestLocation(UUID bookingId) {
        TrackingSessionDto session = getSession(bookingId);
        boolean isTrackingActive = session != null && session.getTrackingActive() != null && session.getTrackingActive();
        String currentStatus = session != null ? session.getStatus().name() : "UNKNOWN";

        // Read cached coordinate from Redis
        try {
            String coordJson = redisTemplate.opsForValue().get(REDIS_BOOKING_PREFIX + bookingId);
            if (coordJson != null && !coordJson.isBlank()) {
                Map<String, Object> map = objectMapper.readValue(coordJson, new TypeReference<>() {});
                Double lat = map.get("lat") != null ? Double.parseDouble(String.valueOf(map.get("lat"))) : null;
                Double lng = map.get("lng") != null ? Double.parseDouble(String.valueOf(map.get("lng"))) : null;
                Double bearing = map.get("bearing") != null ? Double.parseDouble(String.valueOf(map.get("bearing"))) : 0.0;
                Double speed = map.get("speed") != null ? Double.parseDouble(String.valueOf(map.get("speed"))) : 0.0;
                Double accuracy = map.get("accuracy") != null ? Double.parseDouble(String.valueOf(map.get("accuracy"))) : 5.0;
                Long timestamp = map.get("timestamp") != null ? Long.parseLong(String.valueOf(map.get("timestamp"))) : System.currentTimeMillis();

                return TrackingLocationResponse.builder()
                        .bookingId(bookingId.toString())
                        .lat(lat)
                        .lng(lng)
                        .latitude(lat)
                        .longitude(lng)
                        .bearing(bearing)
                        .heading(bearing)
                        .speed(speed)
                        .accuracy(accuracy)
                        .timestamp(timestamp)
                        .trackingActive(isTrackingActive)
                        .status(currentStatus)
                        .found(true)
                        .build();
            }
        } catch (Exception e) {
            logger.warn("Redis coordinate retrieval failed for booking {}: {}", bookingId, e.getMessage());
        }

        // Fallback: check session coordinates
        if (session != null && session.getLastLat() != null && session.getLastLng() != null) {
            return TrackingLocationResponse.builder()
                    .bookingId(bookingId.toString())
                    .lat(session.getLastLat())
                    .lng(session.getLastLng())
                    .latitude(session.getLastLat())
                    .longitude(session.getLastLng())
                    .bearing(session.getBearing() != null ? session.getBearing() : 0.0)
                    .heading(session.getBearing() != null ? session.getBearing() : 0.0)
                    .speed(session.getSpeed() != null ? session.getSpeed() : 0.0)
                    .accuracy(session.getAccuracy() != null ? session.getAccuracy() : 5.0)
                    .timestamp(session.getLastTimestamp() != null ? session.getLastTimestamp() : System.currentTimeMillis())
                    .trackingActive(isTrackingActive)
                    .status(currentStatus)
                    .found(true)
                    .build();
        }

        // Fallback to booking address location
        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking != null && booking.getUserLat() != null && booking.getUserLng() != null) {
            return TrackingLocationResponse.builder()
                    .bookingId(bookingId.toString())
                    .lat(booking.getUserLat())
                    .lng(booking.getUserLng())
                    .latitude(booking.getUserLat())
                    .longitude(booking.getUserLng())
                    .bearing(0.0)
                    .heading(0.0)
                    .speed(0.0)
                    .accuracy(10.0)
                    .timestamp(System.currentTimeMillis())
                    .trackingActive(isTrackingActive)
                    .status(currentStatus)
                    .found(true)
                    .build();
        }

        return TrackingLocationResponse.builder()
                .bookingId(bookingId.toString())
                .trackingActive(false)
                .status(currentStatus)
                .found(false)
                .message("No live location available")
                .build();
    }

    /**
     * Calculates distance and estimated time of arrival.
     * When session is ARRIVED, returns distance = 0 and eta = 0.
     */
    public TrackingDistanceResponse getDistanceAndEta(UUID bookingId, double customerLat, double customerLng) {
        TrackingSessionDto session = getSession(bookingId);
        boolean isArrived = session != null && session.getStatus() == TrackingSessionStatus.ARRIVED;
        boolean isStopped = session != null && session.getStatus() == TrackingSessionStatus.STOPPED;

        if (isArrived || isStopped) {
            return TrackingDistanceResponse.builder()
                    .bookingId(bookingId.toString())
                    .distanceKm(0.0)
                    .distanceMeters(0L)
                    .etaMinutes(0)
                    .distanceText("0 m")
                    .etaText("Arrived")
                    .found(true)
                    .trackingActive(false)
                    .workerLocation(new LatLng(customerLat, customerLng))
                    .build();
        }

        TrackingLocationResponse loc = getLatestLocation(bookingId);
        if (loc.getLat() == null || loc.getLng() == null) {
            return TrackingDistanceResponse.builder()
                    .bookingId(bookingId.toString())
                    .found(false)
                    .trackingActive(false)
                    .build();
        }

        double straightLineKm = haversineKm(loc.getLat(), loc.getLng(), customerLat, customerLng);
        // Urban road circuity factor approx 1.3
        double roadDistKm = Math.round(straightLineKm * 1.3 * 10.0) / 10.0;
        long roadDistMeters = Math.round(roadDistKm * 1000.0);
        // Average urban speed ~ 22 km/h (1 km in ~2.7 mins)
        int etaMins = Math.max(1, (int) Math.ceil((roadDistKm / 22.0) * 60.0));

        String distText = roadDistKm >= 1.0 ? String.format(Locale.US, "%.1f km", roadDistKm) : (roadDistMeters + " m");
        String etaText = etaMins + " mins";

        return TrackingDistanceResponse.builder()
                .bookingId(bookingId.toString())
                .distanceKm(roadDistKm)
                .distanceMeters(roadDistMeters)
                .etaMinutes(etaMins)
                .distanceText(distText)
                .etaText(etaText)
                .found(true)
                .trackingActive(true)
                .workerLocation(new LatLng(loc.getLat(), loc.getLng()))
                .build();
    }

    /**
     * Generates driving route coordinates between origin and destination.
     */
    public TrackingRouteResponse getDrivingRoute(double startLat, double startLng, double destLat, double destLng) {
        double distKm = Math.round(haversineKm(startLat, startLng, destLat, destLng) * 1.3 * 10.0) / 10.0;
        int durationMin = Math.max(1, (int) Math.ceil((distKm / 22.0) * 60.0));

        // Generate interpolated waypoint polyline
        List<double[]> points = new ArrayList<>();
        int steps = 10;
        for (int i = 0; i <= steps; i++) {
            double fraction = (double) i / steps;
            double lat = startLat + (destLat - startLat) * fraction;
            double lng = startLng + (destLng - startLng) * fraction;
            points.add(new double[]{lat, lng});
        }

        return TrackingRouteResponse.builder()
                .coordinates(points)
                .distanceKm(distKm)
                .durationMin(durationMin)
                .found(true)
                .build();
    }

    /**
     * Retrieves the tracking session from Redis.
     */
    public TrackingSessionDto getSession(UUID bookingId) {
        try {
            String json = redisTemplate.opsForValue().get(REDIS_SESSION_PREFIX + bookingId);
            if (json != null && !json.isBlank()) {
                return objectMapper.readValue(json, TrackingSessionDto.class);
            }
        } catch (Exception e) {
            logger.warn("Error reading tracking session for booking {}: {}", bookingId, e.getMessage());
        }
        return null;
    }

    // ── Helper Methods ──

    private void saveSession(TrackingSessionDto session, Duration ttl) {
        try {
            String json = objectMapper.writeValueAsString(session);
            redisTemplate.opsForValue().set(REDIS_SESSION_PREFIX + session.getBookingId(), json, ttl);
        } catch (Exception e) {
            logger.warn("Error saving tracking session for booking {}: {}", session.getBookingId(), e.getMessage());
        }
    }

    private void updateAssignmentKey(UUID bookingId, String status, boolean trackingActive) {
        try {
            Map<String, Object> map = new HashMap<>();
            map.put("bookingId", bookingId.toString());
            map.put("status", status);
            map.put("trackingActive", trackingActive);
            map.put("updatedAt", Instant.now().toString());
            redisTemplate.opsForValue().set(
                    REDIS_ASSIGNMENT_PREFIX + bookingId,
                    objectMapper.writeValueAsString(map),
                    SESSION_TTL
            );
        } catch (Exception ignored) {}
    }

    private void broadcastTrackingEvent(UUID bookingId, UUID customerUserId, TrackingEventMessage event) {
        try {
            // Channel 1: Booking tracking channel
            messagingTemplate.convertAndSend("/topic/tracking/" + bookingId, event);
            // Channel 2: Booking execution track channel
            messagingTemplate.convertAndSend("/topic/track/" + bookingId, event);
            // Channel 3: Booking event stream
            messagingTemplate.convertAndSend("/topic/booking/" + bookingId, event);
            // Channel 4: Customer private channel
            if (customerUserId != null) {
                messagingTemplate.convertAndSend("/topic/customer/" + customerUserId + "/booking", event);
            }
        } catch (Exception e) {
            logger.warn("WebSocket broadcast failed for booking {}: {}", bookingId, e.getMessage());
        }
    }

    private void validateWorkerOwnership(TrackingSessionDto session, UUID workerUserId) {
        if (session.getWorkerUserId() != null && workerUserId != null &&
                !session.getWorkerUserId().equals(workerUserId)) {
            throw new SecurityException("Unauthorized: Worker " + workerUserId + " does not own tracking session for booking " + session.getBookingId());
        }
    }

    private double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371; // Earth radius in km
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}
