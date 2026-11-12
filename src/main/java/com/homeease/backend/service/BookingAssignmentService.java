package com.homeease.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.dto.BookingDto.NearbyWorkerDto;
import com.homeease.backend.dto.WorkerDto.WorkerBookingRequestResponse;
import com.homeease.backend.exception.ResourceNotFoundException;
import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.*;
import com.homeease.backend.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class BookingAssignmentService {

    private static final Logger logger = LoggerFactory.getLogger(BookingAssignmentService.class);

    private final WorkerRepository workerRepository;
    private final WorkerServiceLinkRepository workerServiceLinkRepository;
    private final BookingRepository bookingRepository;
    private final BookingAssignmentRepository assignmentRepository;
    private final ReviewRepository reviewRepository;
    private final NotificationDeliveryRepository deliveryRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final FcmNotificationService fcmNotificationService;
    private final BookingDomainEventService domainEventService;
    private final SimpMessagingTemplate messagingTemplate;
    private final BookingOtpService bookingOtpService;
    private final ObjectMapper objectMapper;
    private final DispatchAuditLogRepository auditLogRepository;
    private final TrackingService trackingService;

    @Value("${homeease.dispatch.radius-meters:5000}")
    private double radiusMeters = 5000.0;

    // Radius expansion tiers (meters): 5km → 10km → 15km → 20km (Task 4)
    private static final double[] RADIUS_EXPANSION_TIERS = {5000.0, 10000.0, 15000.0, 20000.0};

    @Value("${homeease.dispatch.batch-size:5}")
    private int dispatchBatchSize = 5;

    @Value("${homeease.dispatch.response-timeout-seconds:180}")
    private int responseTimeoutSeconds = 180;

    @Autowired(required = false)
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @Autowired
    public BookingAssignmentService(WorkerRepository workerRepository,
                                  WorkerServiceLinkRepository workerServiceLinkRepository,
                                  BookingRepository bookingRepository,
                                  BookingAssignmentRepository assignmentRepository,
                                  ReviewRepository reviewRepository,
                                  NotificationDeliveryRepository deliveryRepository,
                                  RedisTemplate<String, String> redisTemplate,
                                  FcmNotificationService fcmNotificationService,
                                  BookingDomainEventService domainEventService,
                                  SimpMessagingTemplate messagingTemplate,
                                  BookingOtpService bookingOtpService,
                                  ObjectMapper objectMapper,
                                  @Autowired(required = false) DispatchAuditLogRepository auditLogRepository,
                                  @Autowired(required = false) TrackingService trackingService) {
        this.workerRepository = workerRepository;
        this.workerServiceLinkRepository = workerServiceLinkRepository;
        this.bookingRepository = bookingRepository;
        this.assignmentRepository = assignmentRepository;
        this.reviewRepository = reviewRepository;
        this.deliveryRepository = deliveryRepository;
        this.redisTemplate = redisTemplate;
        this.fcmNotificationService = fcmNotificationService;
        this.domainEventService = domainEventService;
        this.messagingTemplate = messagingTemplate;
        this.bookingOtpService = bookingOtpService;
        this.objectMapper = objectMapper;
        this.auditLogRepository = auditLogRepository;
        this.trackingService = trackingService;
    }

    public BookingAssignmentService(WorkerRepository workerRepository,
                                  WorkerServiceLinkRepository workerServiceLinkRepository,
                                  BookingRepository bookingRepository,
                                  BookingAssignmentRepository assignmentRepository,
                                  ReviewRepository reviewRepository,
                                  NotificationDeliveryRepository deliveryRepository,
                                  RedisTemplate<String, String> redisTemplate,
                                  FcmNotificationService fcmNotificationService,
                                  BookingDomainEventService domainEventService,
                                  SimpMessagingTemplate messagingTemplate,
                                  BookingOtpService bookingOtpService,
                                  ObjectMapper objectMapper,
                                  DispatchAuditLogRepository auditLogRepository) {
        this(workerRepository, workerServiceLinkRepository, bookingRepository, assignmentRepository,
             reviewRepository, deliveryRepository, redisTemplate, fcmNotificationService,
             domainEventService, messagingTemplate, bookingOtpService, objectMapper, auditLogRepository, null);
    }

    public BookingAssignmentService(WorkerRepository workerRepository,
                                  WorkerServiceLinkRepository workerServiceLinkRepository,
                                  BookingRepository bookingRepository,
                                  BookingAssignmentRepository assignmentRepository,
                                  ReviewRepository reviewRepository,
                                  NotificationDeliveryRepository deliveryRepository,
                                  RedisTemplate<String, String> redisTemplate,
                                  FcmNotificationService fcmNotificationService,
                                  BookingDomainEventService domainEventService,
                                  SimpMessagingTemplate messagingTemplate,
                                  BookingOtpService bookingOtpService,
                                  ObjectMapper objectMapper) {
        this(workerRepository, workerServiceLinkRepository, bookingRepository, assignmentRepository,
             reviewRepository, deliveryRepository, redisTemplate, fcmNotificationService,
             domainEventService, messagingTemplate, bookingOtpService, objectMapper, null, null);
    }

    /**
     * Dispatch Instant Booking: Finds eligible candidates using radius expansion tiers (5→10→15→20km).
     */
    @Transactional
    public void dispatchInstantBooking(Booking booking, UUID targetSubServiceId) {
        if (booking == null) return;
        UUID serviceId = booking.getService() != null ? booking.getService().getServiceId() : null;
        String servicePincode = booking.getServicePincode();

        logger.info("[WORKER_SEARCH_STARTED] bookingId={}, pincode={}, serviceId={}, subServiceId={}",
                booking.getBookingId(), servicePincode, serviceId, targetSubServiceId);

        List<Worker> eligibleCandidates = new ArrayList<>();
        double usedRadius = radiusMeters;
        Map<String, String> rejectionReasons = new LinkedHashMap<>();

        // -------------------------------------------------------------
        // STRATEGY 1: PINCODE-FIRST MATCHING (DB-filtered, not findAll)
        // -------------------------------------------------------------
        if (servicePincode != null && !servicePincode.isBlank()) {
            // Use index-optimized DB query instead of full table scan
            List<Worker> pincodeWorkers = workerRepository.findOnlineVerifiedWorkersByPincode(servicePincode);
            List<Worker> samePincodeWorkers = pincodeWorkers.stream()
                    .filter(w -> matchesWorkingPincode(w, servicePincode))
                    .filter(w -> {
                        boolean eligible = isCandidateEligible(w, serviceId, targetSubServiceId, servicePincode, rejectionReasons);
                        double dist = calculateHaversineDistance(booking.getUserLat(), booking.getUserLng(),
                                w.getCurrentLat() != null ? w.getCurrentLat() : booking.getUserLat(),
                                w.getCurrentLng() != null ? w.getCurrentLng() : booking.getUserLng());

                        logger.info("[WORKER_PINCODE_MATCH] bookingId={}, workerId={}, workingPincodes={}",
                                booking.getBookingId(), w.getWorkerId(), w.getWorkingPincodes());
                        logger.info("[WORKER_SERVICE_MATCH] bookingId={}, workerId={}, subServiceId={}",
                                booking.getBookingId(), w.getWorkerId(), targetSubServiceId);
                        logger.info("[WORKER_ONLINE_CHECK] bookingId={}, workerId={}, isOnline={}",
                                booking.getBookingId(), w.getWorkerId(), w.getIsOnline());
                        logger.info("[WORKER_GEO_DISTANCE] bookingId={}, workerId={}, distanceMeters={}",
                                booking.getBookingId(), w.getWorkerId(), Math.round(dist));
                        logger.info("[WORKER_ELIGIBILITY] bookingId={}, workerId={}, eligible={}, reason={}",
                                booking.getBookingId(), w.getWorkerId(), eligible, eligible ? "OK" : rejectionReasons.getOrDefault(w.getWorkerId().toString(), "UNKNOWN"));
                        return eligible;
                    })
                    .sorted(Comparator.comparingDouble(w ->
                            calculateHaversineDistance(booking.getUserLat(), booking.getUserLng(),
                                    w.getCurrentLat() != null ? w.getCurrentLat() : booking.getUserLat(),
                                    w.getCurrentLng() != null ? w.getCurrentLng() : booking.getUserLng())))
                    .toList();

            if (!samePincodeWorkers.isEmpty()) {
                eligibleCandidates = new ArrayList<>(samePincodeWorkers);
                usedRadius = 5000.0;
                logger.info("Found {} eligible workers matching pincode {} for booking {}",
                        eligibleCandidates.size(), servicePincode, booking.getBookingId());
            }
        }

        // -------------------------------------------------------------
        // STRATEGY 2: RADIUS EXPANSION TIERS (If no candidates in same pincode)
        // -------------------------------------------------------------
        if (eligibleCandidates.isEmpty()) {
            for (double tier : RADIUS_EXPANSION_TIERS) {
                rejectionReasons.clear();
                eligibleCandidates = findEligibleCandidateWorkers(
                        serviceId,
                        targetSubServiceId,
                        booking.getUserLat(),
                        booking.getUserLng(),
                        tier,
                        servicePincode,
                        rejectionReasons
                );
                usedRadius = tier;
                if (!eligibleCandidates.isEmpty()) {
                    logger.info("Found {} eligible workers within {} m for booking {}", eligibleCandidates.size(), tier, booking.getBookingId());
                    break;
                }
                logger.info("No workers found within {} m for booking {}, expanding radius...", tier, booking.getBookingId());
            }
        }

        logger.info("PARTNER_MATCHING: bookingId={}, pincode={}, eligiblePartnerCount={}",
                booking.getBookingId(), servicePincode, eligibleCandidates.size());

        for (Worker worker : eligibleCandidates) {
            logger.info("[WORKER_SELECTED] bookingId={}, workerId={}", booking.getBookingId(), worker.getWorkerId());
        }

        // Record Dispatch Audit Log
        recordDispatchAuditLog(
                booking,
                serviceId,
                usedRadius,
                eligibleCandidates.size(),
                rejectionReasons,
                eligibleCandidates.isEmpty() ? null : eligibleCandidates.get(0).getWorkerId(),
                "CONSUMED",
                eligibleCandidates.isEmpty() ? "SKIPPED" : "SENT",
                eligibleCandidates.isEmpty() ? "SKIPPED" : "SENT",
                eligibleCandidates.isEmpty() ? "WORKER_NOT_FOUND" : "SEARCHING"
        );

        if (eligibleCandidates.isEmpty()) {
            String primaryReason = "NO_WORKER_IN_PINCODE";
            if (rejectionReasons.containsValue("WORKER_BUSY")) primaryReason = "WORKER_ALREADY_BUSY";
            else if (rejectionReasons.containsValue("WORKER_OFFLINE")) primaryReason = "WORKER_OFFLINE";
            else if (rejectionReasons.containsValue("LOCATION_STALE")) primaryReason = "LOCATION_STALE";
            else if (rejectionReasons.containsValue("SERVICE_NOT_SUPPORTED")) primaryReason = "SERVICE_NOT_SUPPORTED";
            else if (rejectionReasons.containsValue("OUTSIDE_RADIUS")) primaryReason = "OUTSIDE_ALLOWED_DISTANCE";

            logger.warn("[WORKER_ELIGIBILITY] bookingId={}, eligible=false, reason={}",
                    booking.getBookingId(), primaryReason);
            logger.warn("No eligible workers found in any radius tier for booking {} (reason: {})",
                    booking.getBookingId(), primaryReason);
            booking.setStatus(BookingStage.WORKER_NOT_FOUND);
            bookingRepository.save(booking);

            notifyCustomerWorkerNotFound(booking);
            return;
        }

        // Dispatch to first batch of size dispatchBatchSize
        dispatchToBatch(booking, eligibleCandidates, 1);
    }

    /**
     * Dispatches booking request to a specific batch of workers.
     */
    @Transactional
    public void dispatchToBatch(Booking booking, List<Worker> candidates, int attemptNo) {
        if (booking.getStatus() != BookingStage.REQUESTED &&
            booking.getStatus() != BookingStage.SEARCHING &&
            booking.getStatus() != BookingStage.PENDING) {
            logger.info("Booking {} is no longer searching for workers (current status: {})",
                    booking.getBookingId(), booking.getStatus());
            return;
        }

        booking.setStatus(BookingStage.SEARCHING);
        bookingRepository.save(booking);

        Instant expiresAt = Instant.now().plusSeconds(responseTimeoutSeconds);
        int batchCount = 0;

        for (Worker worker : candidates) {
            if (batchCount >= dispatchBatchSize) {
                break;
            }

            // Check if already dispatched to this worker
            Optional<BookingAssignment> existing = assignmentRepository
                    .findByBooking_BookingIdAndWorker_WorkerId(booking.getBookingId(), worker.getWorkerId());
            if (existing.isPresent()) {
                continue;
            }

            BookingAssignment assignment = BookingAssignment.builder()
                    .booking(booking)
                    .worker(worker)
                    .status(AssignmentStatus.PENDING)
                    .attemptNo(attemptNo)
                    .requestedAt(Instant.now())
                    .expiresAt(expiresAt)
                    .build();

            assignmentRepository.save(assignment);
            batchCount++;

            // Alert worker via WebSocket & FCM
            sendWorkerJobAlert(worker, booking);
        }

        logger.info("Dispatched booking {} to {} candidate workers (batch {})",
                booking.getBookingId(), batchCount, attemptNo);
    }

    /**
     * Worker acceptance logic with atomic lock and guarantee that exactly one worker wins.
     */
    @Transactional
    public Booking handleWorkerAcceptance(UUID bookingId, UUID workerUserId) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));

        if (booking.getStatus() != BookingStage.SEARCHING &&
            booking.getStatus() != BookingStage.REQUESTED &&
            booking.getStatus() != BookingStage.PENDING) {
            throw new IllegalStateException("Booking is no longer searching for workers. Current status: " + booking.getStatus());
        }

        Worker worker = workerRepository.findByUser_UserId(workerUserId)
                .or(() -> workerRepository.findById(workerUserId))
                .orElseThrow(() -> new ResourceNotFoundException("Worker profile not found for user: " + workerUserId));

        // Generate preliminary 4-digit PIN
        String pinCode = String.format("%04d", new SecureRandom().nextInt(10000));

        booking.setWorker(worker);
        booking.setStatus(BookingStage.ACCEPTED);
        booking.setWorkerAssignedAt(Instant.now());
        booking.setPinCode(pinCode);
        booking = bookingRepository.save(booking);

        logger.info("[WORKER_ACCEPTED] bookingId={}, workerId={}", bookingId, worker.getWorkerId());
        logger.info("BOOKING_ASSIGNED: bookingId={}, partnerId={}", bookingId, worker.getWorkerId());

        if (meterRegistry != null) {
            try {
                meterRegistry.counter("homeease.dispatch.assigned",
                        "pincode", booking.getServicePincode() != null ? booking.getServicePincode() : "unknown").increment();
            } catch (Exception ignored) {}
        }

        // Update winning assignment to ACCEPTED
        assignmentRepository.findByBooking_BookingIdAndWorker_WorkerId(bookingId, worker.getWorkerId())
                .ifPresent(a -> {
                    a.setStatus(AssignmentStatus.ACCEPTED);
                    a.setRespondedAt(Instant.now());
                    a.setAcceptedAt(Instant.now());
                    assignmentRepository.save(a);
                });

        // Drop all other pending assignments for this booking
        assignmentRepository.dropOtherPendingAssignments(bookingId, worker.getWorkerId(), AssignmentStatus.DROPPED, Instant.now());

        // Notify customer
        notifyCustomerWorkerAssigned(booking, worker, pinCode);

        // Record domain event
        try {
            domainEventService.recordEvent(
                    booking,
                    BookingEventType.PARTNER_ACCEPTED,
                    BookingStage.SEARCHING,
                    BookingStage.ACCEPTED,
                    BookingActorType.WORKER,
                    workerUserId
            );
        } catch (Exception e) {
            logger.warn("Domain event record skipped: {}", e.getMessage());
        }

        // Cache tracking assignment in Redis
        cacheTrackingAssignment(booking, worker, pinCode);

        // Initialize authoritative tracking session
        if (trackingService != null) {
            try {
                trackingService.createTrackingSession(booking);
            } catch (Exception e) {
                logger.warn("Tracking session creation skipped for booking {}: {}", bookingId, e.getMessage());
            }
        }

        // Record acceptance in dispatch audit log
        recordDispatchAuditLog(
                booking,
                booking.getService() != null ? booking.getService().getServiceId() : null,
                radiusMeters,
                1,
                Collections.emptyMap(),
                worker.getWorkerId(),
                "CONSUMED",
                "DELIVERED",
                "DELIVERED",
                "ACCEPTED"
        );

        logger.info("Worker {} successfully accepted booking {}", worker.getWorkerId(), bookingId);
        return booking;
    }

    /**
     * Worker decline / rejection logic.
     */
    @Transactional
    public void handleWorkerRejection(UUID bookingId, UUID workerUserId, UUID targetSubServiceId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));

        Worker worker = workerRepository.findByUser_UserId(workerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Worker profile not found: " + workerUserId));

        assignmentRepository.findByBooking_BookingIdAndWorker_WorkerId(bookingId, worker.getWorkerId())
                .ifPresent(a -> {
                    a.setStatus(AssignmentStatus.REJECTED);
                    a.setRespondedAt(Instant.now());
                    assignmentRepository.save(a);
                });

        logger.info("Worker {} rejected booking {}", worker.getWorkerId(), bookingId);

        // If no more active pending assignments exist for this booking, cascade to next batch
        List<BookingAssignment> pending = assignmentRepository.findByBooking_BookingIdAndStatus(bookingId, AssignmentStatus.PENDING);
        if (pending.isEmpty() && booking.getStatus() == BookingStage.SEARCHING) {
            cascadeToNextBatch(booking, targetSubServiceId);
        }
    }

    /**
     * Worker start travel: transitions booking from ACCEPTED/CONFIRMED to ON_THE_WAY and broadcasts update.
     */
    @Transactional
    public Booking handleWorkerStartTravel(UUID bookingId, UUID workerUserId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));

        boolean isAssignedWorker = booking.getWorker() != null && (
                (booking.getWorker().getUser() != null && booking.getWorker().getUser().getUserId().equals(workerUserId)) ||
                booking.getWorker().getWorkerId().equals(workerUserId)
        );
        if (!isAssignedWorker) {
            throw new SecurityException("Unauthorized: Only the assigned worker can start travel.");
        }

        if (booking.getStatus() == BookingStage.ACCEPTED || booking.getStatus() == BookingStage.CONFIRMED) {
            booking.setStatus(BookingStage.ON_THE_WAY);
            booking = bookingRepository.save(booking);
            broadcastBookingUpdate(booking, "WORKER_ON_THE_WAY");

            if (trackingService != null) {
                try {
                    trackingService.startTravelling(bookingId, workerUserId);
                } catch (Exception e) {
                    logger.warn("Tracking startTravelling skipped for booking {}: {}", bookingId, e.getMessage());
                }
            }

            logger.info("Worker {} started travelling for booking {}", workerUserId, bookingId);
        }
        return booking;
    }

    /**
     * Worker arrival verification: marks booking as ARRIVED and issues 4-digit OTP to customer.
     */
    @Transactional
    public Booking handleWorkerArrival(UUID bookingId, UUID workerUserId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));

        boolean isAssignedWorker = booking.getWorker() != null && (
                (booking.getWorker().getUser() != null && booking.getWorker().getUser().getUserId().equals(workerUserId)) ||
                booking.getWorker().getWorkerId().equals(workerUserId)
        );
        if (!isAssignedWorker) {
            throw new SecurityException("Unauthorized: Only the assigned worker can confirm arrival.");
        }

        // Idempotency check: If booking is already at or past ARRIVED stage, return gracefully
        if (booking.getStatus() == BookingStage.ARRIVED ||
            booking.getStatus() == BookingStage.IN_PROGRESS ||
            booking.getStatus() == BookingStage.COMPLETED) {
            logger.info("Worker {} arrival check: booking {} is already in stage {} - returning idempotent response",
                    workerUserId, bookingId, booking.getStatus());
            return booking;
        }

        if (booking.getStatus() != BookingStage.ACCEPTED &&
            booking.getStatus() != BookingStage.CONFIRMED &&
            booking.getStatus() != BookingStage.ON_THE_WAY) {
            throw new IllegalStateException("Cannot arrive for booking in status: " + booking.getStatus());
        }

        Instant arrivedAt = Instant.now();
        booking.setStatus(BookingStage.ARRIVED);
        booking.setArrivedAt(arrivedAt);
        booking = bookingRepository.save(booking);

        // Issue 4-digit OTP to customer
        bookingOtpService.issueOtp(booking);

        // Broadcast arrival status to customer & worker
        broadcastBookingUpdate(booking, "WORKER_ARRIVED");

        // F1 Fix: Update tracking:assignment Redis key to ARRIVED status
        // trackingActive is explicitly false so customer tracking stops immediately
        try {
            String assignmentKey = "tracking:assignment:" + bookingId;
            String existingJson = redisTemplate.opsForValue().get(assignmentKey);
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            if (existingJson != null && !existingJson.isBlank()) {
                map = objectMapper.readValue(existingJson,
                        new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
            }
            map.put("status", "ARRIVED");
            map.put("arrivedAt", arrivedAt.toString());
            map.put("trackingActive", false);
            redisTemplate.opsForValue().set(assignmentKey, objectMapper.writeValueAsString(map), Duration.ofHours(24));

            // Broadcast ARRIVED tracking event so customer app reacts instantly via WebSocket
            java.util.Map<String, Object> event = new java.util.HashMap<>();
            event.put("bookingId", bookingId.toString());
            event.put("status", "ARRIVED");
            event.put("trackingActive", false);
            event.put("message", "Partner has arrived at your location.");
            messagingTemplate.convertAndSend("/topic/track/" + bookingId, event);
            messagingTemplate.convertAndSend("/topic/tracking/" + bookingId, event);
        } catch (Exception e) {
            logger.warn("Redis tracking assignment ARRIVED update failed for booking {}: {}", bookingId, e.getMessage());
        }

        // Authoritative tracking auto-stop & session transition to ARRIVED
        if (trackingService != null) {
            try {
                trackingService.handleArrival(bookingId, workerUserId);
            } catch (Exception e) {
                logger.warn("TrackingService handleArrival failed for booking {}: {}", bookingId, e.getMessage());
            }
        }

        logger.info("Worker {} arrived at customer location for booking {}", workerUserId, bookingId);
        return booking;
    }

    /**
     * Periodic scheduled poller (every 10 seconds): checks expired pending assignments and cascades.
     */
    @Scheduled(fixedDelay = 10000)
    @Transactional
    public void processExpiredAssignments() {
        Instant now = Instant.now();
        List<BookingAssignment> expiredList = assignmentRepository.findByStatusAndExpiresAtBefore(AssignmentStatus.PENDING, now);

        if (expiredList.isEmpty()) {
            return;
        }

        Set<UUID> affectedBookings = new HashSet<>();
        for (BookingAssignment assignment : expiredList) {
            assignment.setStatus(AssignmentStatus.TIMEOUT);
            assignment.setRespondedAt(now);
            assignmentRepository.save(assignment);
            if (assignment.getBooking() != null) {
                affectedBookings.add(assignment.getBooking().getBookingId());
            }
        }

        for (UUID bookingId : affectedBookings) {
            bookingRepository.findById(bookingId).ifPresent(booking -> {
                if (booking.getStatus() == BookingStage.SEARCHING || booking.getStatus() == BookingStage.REQUESTED) {
                    List<BookingAssignment> remainingPending = assignmentRepository
                            .findByBooking_BookingIdAndStatus(bookingId, AssignmentStatus.PENDING);
                    if (remainingPending.isEmpty()) {
                        cascadeToNextBatch(booking, null);
                    }
                }
            });
        }
    }

    private void cascadeToNextBatch(Booking booking, UUID targetSubServiceId) {
        UUID serviceId = booking.getService() != null ? booking.getService().getServiceId() : null;
        List<Worker> allCandidates = new ArrayList<>();
        for (double tier : RADIUS_EXPANSION_TIERS) {
            allCandidates = findEligibleCandidateWorkers(
                    serviceId,
                    targetSubServiceId,
                    booking.getUserLat(),
                    booking.getUserLng(),
                    tier
            );
            if (!allCandidates.isEmpty()) break;
        }

        // Filter out workers who already received an assignment for this booking
        List<BookingAssignment> pastAssignments = assignmentRepository.findByBooking_BookingId(booking.getBookingId());
        Set<UUID> attemptedWorkerIds = new HashSet<>();
        for (BookingAssignment a : pastAssignments) {
            attemptedWorkerIds.add(a.getWorker().getWorkerId());
        }

        List<Worker> nextCandidates = allCandidates.stream()
                .filter(w -> !attemptedWorkerIds.contains(w.getWorkerId()))
                .toList();

        if (nextCandidates.isEmpty()) {
            logger.warn("Exhausted all eligible worker batches for booking {}", booking.getBookingId());
            booking.setStatus(BookingStage.WORKER_NOT_FOUND);
            bookingRepository.save(booking);
            notifyCustomerWorkerNotFound(booking);

            recordDispatchAuditLog(
                    booking,
                    serviceId,
                    radiusMeters,
                    0,
                    Map.of("ALL_WORKERS", "EXHAUSTED_OR_DECLINED"),
                    null,
                    "CONSUMED",
                    "SENT",
                    "SENT",
                    "WORKER_NOT_FOUND"
            );
        } else {
            int nextAttempt = pastAssignments.size() / dispatchBatchSize + 1;
            dispatchToBatch(booking, nextCandidates, nextAttempt);
        }
    }

    /**
     * Candidate discovery: Redis GEO + PostGIS fallback with strict category, skills, and online filtering.
     */
    public List<Worker> findEligibleCandidateWorkers(UUID serviceId, UUID subServiceId,
                                                    double userLat, double userLng,
                                                    double effectiveRadius) {
        return findEligibleCandidateWorkers(serviceId, subServiceId, userLat, userLng, effectiveRadius, null, new LinkedHashMap<>());
    }

    public List<Worker> findEligibleCandidateWorkers(UUID serviceId, UUID subServiceId,
                                                    double userLat, double userLng,
                                                    double effectiveRadius,
                                                    Map<String, String> rejectionReasons) {
        return findEligibleCandidateWorkers(serviceId, subServiceId, userLat, userLng, effectiveRadius, null, rejectionReasons);
    }

    public List<Worker> findEligibleCandidateWorkers(UUID serviceId, UUID subServiceId,
                                                    double userLat, double userLng,
                                                    double effectiveRadius,
                                                    String servicePincode,
                                                    Map<String, String> rejectionReasons) {
        List<Worker> candidates = new ArrayList<>();
        Set<UUID> workerIds = new HashSet<>();

        if (Double.isNaN(userLat) || Double.isNaN(userLng) || Math.abs(userLat) > 90.0 || Math.abs(userLng) > 180.0) {
            logger.warn("Invalid customer coordinates for dispatch: lat={}, lng={}", userLat, userLng);
            return Collections.emptyList();
        }

        // 1. Redis GEO radius query — query active_workers_geo, fallback to geo:workers:global
        try {
            double radiusKm = effectiveRadius / 1000.0;
            Circle searchArea = new Circle(new Point(userLng, userLat), new Distance(radiusKm, Metrics.KILOMETERS));
            GeoResults<RedisGeoCommands.GeoLocation<String>> results =
                    redisTemplate.opsForGeo().radius("active_workers_geo", searchArea);
            if (results == null || results.getContent().isEmpty()) {
                results = redisTemplate.opsForGeo().radius("geo:workers:global", searchArea);
            }
            if (results != null && !results.getContent().isEmpty()) {
                for (var res : results.getContent()) {
                    try {
                        UUID candidateId = UUID.fromString(res.getContent().getName());
                        Worker worker = workerRepository.findById(candidateId)
                                .or(() -> workerRepository.findByUser_UserId(candidateId))
                                .orElse(null);
                        if (worker != null) {
                            if (isCandidateEligible(worker, serviceId, subServiceId, servicePincode, rejectionReasons) && workerIds.add(worker.getWorkerId())) {
                                candidates.add(worker);
                            }
                        } else {
                            if (rejectionReasons != null) {
                                rejectionReasons.put(candidateId.toString(), "WORKER_NOT_AVAILABLE");
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            logger.warn("Redis GEO lookup fallback to PostGIS query: {}", e.getMessage());
        }

        // 2. PostGIS Fallback Query if Redis GEO returned no eligible candidates
        if (candidates.isEmpty() && subServiceId != null) {
            try {
                List<Worker> dbNearby = workerRepository.findCandidateWorkersNearby(
                        subServiceId.toString(), userLat, userLng, effectiveRadius);
                for (Worker w : dbNearby) {
                    if (isCandidateEligible(w, serviceId, subServiceId, servicePincode, rejectionReasons) && workerIds.add(w.getWorkerId())) {
                        candidates.add(w);
                    }
                }
            } catch (Exception e) {
                logger.warn("PostGIS candidate query exception: {}", e.getMessage());
            }
        }

        // 3. Fallback: query active verified online workers
        if (candidates.isEmpty()) {
            List<Worker> onlineWorkers = workerRepository.findAll().stream()
                    .filter(w -> {
                        if (w.getCurrentLat() == null || w.getCurrentLng() == null) {
                            if (rejectionReasons != null) rejectionReasons.put(w.getWorkerId() != null ? w.getWorkerId().toString() : "unknown", "LOCATION_MISSING");
                            return false;
                        }
                        double dist = calculateHaversineDistance(userLat, userLng, w.getCurrentLat(), w.getCurrentLng());
                        if (dist > effectiveRadius) {
                            if (rejectionReasons != null) rejectionReasons.put(w.getWorkerId() != null ? w.getWorkerId().toString() : "unknown", "OUTSIDE_RADIUS");
                            return false;
                        }
                        return isCandidateEligible(w, serviceId, subServiceId, servicePincode, rejectionReasons);
                    })
                    .toList();
            for (Worker w : onlineWorkers) {
                if (workerIds.add(w.getWorkerId())) {
                    candidates.add(w);
                }
            }
        }

        // Sort candidates by proximity to customer
        candidates.sort(Comparator.comparingDouble(w ->
                calculateHaversineDistance(userLat, userLng,
                        w.getCurrentLat() != null ? w.getCurrentLat() : userLat,
                        w.getCurrentLng() != null ? w.getCurrentLng() : userLng)));

        return candidates;
    }

    public boolean isCandidateEligible(Worker worker, UUID targetSubServiceId) {
        return isCandidateEligible(worker, null, targetSubServiceId, null, new LinkedHashMap<>());
    }

    public boolean isCandidateEligible(Worker worker, UUID serviceId, UUID targetSubServiceId, Map<String, String> rejectionReasons) {
        return isCandidateEligible(worker, serviceId, targetSubServiceId, null, rejectionReasons);
    }

    public boolean isCandidateEligible(Worker worker, UUID serviceId, UUID targetSubServiceId, String servicePincode, Map<String, String> rejectionReasons) {
        if (worker == null) return false;
        String wId = worker.getWorkerId() != null ? worker.getWorkerId().toString() : "unknown";

        // 1. Online check
        if (!Boolean.TRUE.equals(worker.getIsOnline())) {
            if (rejectionReasons != null) rejectionReasons.put(wId, "WORKER_OFFLINE");
            return false;
        }

        // 2. Verification check
        if (!Boolean.TRUE.equals(worker.getIsVerified())) {
            if (rejectionReasons != null) rejectionReasons.put(wId, "WORKER_NOT_APPROVED");
            return false;
        }

        // 3. Blocked / Penalty penalty check
        if (worker.getBlockedUntil() != null && worker.getBlockedUntil().isAfter(Instant.now())) {
            if (rejectionReasons != null) rejectionReasons.put(wId, "WORKER_NOT_AVAILABLE");
            return false;
        }

        // 4. Active job check
        List<BookingStage> activeStages = List.of(
                BookingStage.ACCEPTED,
                BookingStage.CONFIRMED,
                BookingStage.ON_THE_WAY,
                BookingStage.ARRIVED,
                BookingStage.IN_PROGRESS
        );
        List<Booking> activeBookings = bookingRepository.findByWorker_WorkerIdAndStatusInOrderByScheduledAtAsc(
                worker.getWorkerId(), activeStages);
        if (!activeBookings.isEmpty()) {
            if (rejectionReasons != null) rejectionReasons.put(wId, "WORKER_BUSY");
            return false;
        }

        List<BookingAssignment> activeAssignments = assignmentRepository.findByWorker_WorkerIdAndStatus(
                worker.getWorkerId(), AssignmentStatus.ACCEPTED);
        boolean hasActiveAcceptedJob = activeAssignments.stream()
                .anyMatch(a -> a.getBooking() != null && activeStages.contains(a.getBooking().getStatus()));
        if (hasActiveAcceptedJob) {
            if (rejectionReasons != null) rejectionReasons.put(wId, "WORKER_BUSY");
            return false;
        }

        // 5. Location validity & freshness check
        if (worker.getCurrentLat() == null || worker.getCurrentLng() == null) {
            if (rejectionReasons != null) rejectionReasons.put(wId, "LOCATION_MISSING");
            return false;
        }
        if (Math.abs(worker.getCurrentLat()) > 90.0 || Math.abs(worker.getCurrentLng()) > 180.0 ||
            (worker.getCurrentLat() == 0.0 && worker.getCurrentLng() == 0.0)) {
            if (rejectionReasons != null) rejectionReasons.put(wId, "LOCATION_MISSING");
            return false;
        }
        if (worker.getLastLocationUpdatedAt() != null) {
            boolean samePincode = matchesWorkingPincode(worker, servicePincode);
            long thresholdMinutes = samePincode ? 1440 : 30; // 24 hours if online partner in same pincode
            if (worker.getLastLocationUpdatedAt().isBefore(Instant.now().minus(thresholdMinutes, ChronoUnit.MINUTES))) {
                if (rejectionReasons != null) rejectionReasons.put(wId, "LOCATION_STALE");
                return false;
            }
        }

        // 6. Category / Sub-service trade skill qualification check
        if (targetSubServiceId != null || serviceId != null) {
            boolean qualified = false;

            if (targetSubServiceId != null) {
                qualified = workerServiceLinkRepository.existsById_WorkerIdAndId_SubServiceId(worker.getWorkerId(), targetSubServiceId)
                        || workerServiceLinkRepository.existsByWorkerIdAndSubServiceOrServiceId(worker.getWorkerId(), targetSubServiceId);
            }

            if (!qualified && serviceId != null) {
                qualified = workerServiceLinkRepository.existsByWorkerIdAndServiceId(worker.getWorkerId(), serviceId);
            }

            if (!qualified) {
                if (rejectionReasons != null) rejectionReasons.put(wId, "SERVICE_NOT_SUPPORTED");
                return false;
            }
        }

        // 7. Working pincodes check (if partner configured preferred/working pincodes)
        if (servicePincode != null && !servicePincode.isBlank() &&
            worker.getWorkingPincodes() != null && !worker.getWorkingPincodes().isBlank()) {
            if (!matchesWorkingPincode(worker, servicePincode)) {
                if (rejectionReasons != null) rejectionReasons.put(wId, "PINCODE_NOT_SERVICED");
                return false;
            }
        }

        return true;
    }

    public boolean matchesWorkingPincode(Worker worker, String servicePincode) {
        if (worker == null || servicePincode == null || servicePincode.isBlank()) return false;
        String cleanPin = servicePincode.replaceAll("\\D", "");
        if (cleanPin.length() != 6) return false;

        if (worker.getWorkingPincodes() != null && !worker.getWorkingPincodes().isBlank()) {
            List<String> allowedPins = Arrays.stream(worker.getWorkingPincodes().split("[,;|\\s]+"))
                    .map(s -> s.replaceAll("\\D", ""))
                    .filter(s -> !s.isBlank())
                    .toList();
            if (allowedPins.contains(cleanPin)) {
                return true;
            }
        }
        // Fallback: If worker is physically situated near that pincode centroid (<6.5km)
        if (worker.getCurrentLat() != null && worker.getCurrentLng() != null) {
            if ("500010".equals(cleanPin)) {
                double dist = calculateHaversineDistance(17.5028, 78.5078, worker.getCurrentLat(), worker.getCurrentLng());
                if (dist <= 6500.0) return true;
            } else if ("500072".equals(cleanPin)) {
                double dist = calculateHaversineDistance(17.4947, 78.3996, worker.getCurrentLat(), worker.getCurrentLng());
                if (dist <= 6500.0) return true;
            }
        }
        return false;
    }

    public List<NearbyWorkerDto> findNearbyAvailableWorkers(UUID serviceId, UUID subServiceId,
                                                          double userLat, double userLng,
                                                          Double radiusMeters) {
        double effectiveRadius = (radiusMeters != null && radiusMeters > 0) ? radiusMeters : this.radiusMeters;
        List<Worker> candidates = findEligibleCandidateWorkers(serviceId, subServiceId, userLat, userLng, effectiveRadius);

        List<NearbyWorkerDto> dtoList = new ArrayList<>();
        for (Worker w : candidates) {
            double distanceMeters = calculateHaversineDistance(
                    userLat, userLng,
                    w.getCurrentLat() != null ? w.getCurrentLat() : userLat,
                    w.getCurrentLng() != null ? w.getCurrentLng() : userLng);
            int etaMins = Math.max(10, (int) Math.round((distanceMeters / 400.0) + 5));

            dtoList.add(NearbyWorkerDto.builder()
                    .workerId(w.getWorkerId())
                    .fullName(w.getUser() != null ? w.getUser().getFullName() : "HomeEase Partner")
                    .rating(4.8)
                    .distanceMeters((double) Math.round(distanceMeters))
                    .estimatedArrivalMinutes(etaMins)
                    .avatarUrl(w.getAvatarUrl())
                    .isOnline(w.getIsOnline())
                    .build());
        }
        return dtoList;
    }

    @Transactional(readOnly = true)
    public List<WorkerBookingRequestResponse> getPendingRequestsForWorker(UUID userId) {
        Worker worker = workerRepository.findByUser_UserId(userId)
                .or(() -> workerRepository.findById(userId))
                .orElse(null);
        if (worker == null) {
            return Collections.emptyList();
        }

        List<BookingAssignment> pending = assignmentRepository
                .findByWorker_WorkerIdAndStatus(worker.getWorkerId(), AssignmentStatus.PENDING);

        List<WorkerBookingRequestResponse> requests = new ArrayList<>();
        Instant now = Instant.now();

        for (BookingAssignment assignment : pending) {
            if (assignment.getExpiresAt().isBefore(now)) {
                continue;
            }

            Booking booking = assignment.getBooking();
            if (booking != null && (booking.getStatus() == BookingStage.SEARCHING ||
                                    booking.getStatus() == BookingStage.REQUESTED ||
                                    booking.getStatus() == BookingStage.PENDING)) {

                String serviceName = booking.getService() != null ? booking.getService().getName() : "Home Service";
                String customerName = booking.getRecipientName() != null && !booking.getRecipientName().isBlank()
                        ? booking.getRecipientName()
                        : (booking.getUser() != null ? booking.getUser().getFullName() : "Customer");
                String customerPhone = booking.getRecipientPhone() != null && !booking.getRecipientPhone().isBlank()
                        ? booking.getRecipientPhone()
                        : (booking.getUser() != null ? booking.getUser().getPhoneNumber() : "");
                String customerAddress = booking.getRecipientAddress() != null && !booking.getRecipientAddress().isBlank()
                        ? booking.getRecipientAddress()
                        : "Customer Location";

                requests.add(WorkerBookingRequestResponse.builder()
                        .bookingId(booking.getBookingId())
                        .bookingType(booking.getBookingType() != null ? booking.getBookingType().name() : "INSTANT")
                        .status(booking.getStatus().name())
                        .serviceName(serviceName)
                        .subServiceName(serviceName)
                        .customerName(customerName)
                        .customerPhone(customerPhone)
                        .customerAddress(customerAddress)
                        .pincode(booking.getServicePincode())
                        .servicePincode(booking.getServicePincode())
                        .userLat(booking.getUserLat())
                        .userLng(booking.getUserLng())
                        .totalAmount(booking.getTotalAmount())
                        .scheduledAt(booking.getScheduledAt())
                        .scheduledEndAt(booking.getScheduledEndAt())
                        .estimatedArrivalMinutes(booking.getEstimatedArrivalMinutes())
                        .createdAt(booking.getCreatedAt())
                        .build());
            }
        }
        logger.info("[WORKER_RECEIVED] workerUserId={}, pendingCount={}", userId, requests.size());
        return requests;
    }

    private void sendWorkerJobAlert(Worker worker, Booking booking) {
        String serviceName = booking.getService() != null ? booking.getService().getName() : "Home Service";
        String customerName = booking.getRecipientName() != null && !booking.getRecipientName().isBlank()
                ? booking.getRecipientName()
                : (booking.getUser() != null ? booking.getUser().getFullName() : "Customer");
        String customerPhone = booking.getRecipientPhone() != null && !booking.getRecipientPhone().isBlank()
                ? booking.getRecipientPhone()
                : (booking.getUser() != null ? booking.getUser().getPhoneNumber() : "");
        String customerAddress = booking.getRecipientAddress() != null && !booking.getRecipientAddress().isBlank()
                ? booking.getRecipientAddress()
                : "Customer Location";

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "NEW_JOB_REQUEST");
        payload.put("bookingId", booking.getBookingId().toString());
        payload.put("serviceName", serviceName);
        payload.put("subServiceName", serviceName);
        payload.put("bookingType", booking.getBookingType() != null ? booking.getBookingType().name() : "INSTANT");
        payload.put("totalAmount", booking.getTotalAmount());
        payload.put("customerName", customerName);
        payload.put("customerPhone", customerPhone);
        payload.put("customerAddress", customerAddress);
        payload.put("pincode", booking.getServicePincode());
        payload.put("servicePincode", booking.getServicePincode());
        payload.put("customerLat", booking.getUserLat());
        payload.put("customerLng", booking.getUserLng());
        payload.put("userLat", booking.getUserLat());
        payload.put("userLng", booking.getUserLng());
        payload.put("status", booking.getStatus() != null ? booking.getStatus().name() : "SEARCHING");
        payload.put("createdAt", booking.getCreatedAt() != null ? booking.getCreatedAt().toString() : Instant.now().toString());

        logger.info("[WORKER_NOTIFICATION_SENT] bookingId={}, workerId={}, channel=WS_AND_FCM", booking.getBookingId(), worker.getWorkerId());
        logger.info("PARTNER_OFFERED: bookingId={}, partnerId={}", booking.getBookingId(), worker.getWorkerId());

        // 1. WebSocket topic dispatch
        try {
            messagingTemplate.convertAndSend("/topic/worker/" + worker.getWorkerId() + "/requests", payload);
            messagingTemplate.convertAndSend("/topic/worker/" + worker.getWorkerId() + "/stream", payload);
            if (worker.getUser() != null) {
                messagingTemplate.convertAndSend("/topic/worker/" + worker.getUser().getUserId() + "/requests", payload);
                messagingTemplate.convertAndSend("/topic/worker/" + worker.getUser().getUserId() + "/stream", payload);
            }
            recordNotificationDelivery(booking, null, worker, DeliveryChannel.WS, "/topic/worker/" + worker.getWorkerId() + "/requests", DeliveryStatus.SENT);
        } catch (Exception e) {
            logger.debug("WebSocket alert dispatch failed: {}", e.getMessage());
        }

        // 2. FCM Push Notification
        if (worker.getUser() != null) {
            try {
                fcmNotificationService.sendPushNotification(
                        worker.getUser().getUserId(),
                        "New Nearby Job Request!",
                        "A customer requested " + serviceName + " near your location. Tap to accept!"
                );
                recordNotificationDelivery(booking, null, worker, DeliveryChannel.FCM, "FCM_PUSH", DeliveryStatus.SENT);
            } catch (Exception e) {
                logger.debug("FCM alert dispatch failed: {}", e.getMessage());
            }
        }
    }

    private void notifyCustomerWorkerAssigned(Booking booking, Worker worker, String pinCode) {
        if (booking.getUser() == null) return;
        UUID customerId = booking.getUser().getUserId();
        String workerName = worker.getUser() != null ? worker.getUser().getFullName() : "HomeEase Partner";

        // WebSocket
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "WORKER_ASSIGNED");
            payload.put("bookingId", booking.getBookingId().toString());
            payload.put("workerId", worker.getWorkerId().toString());
            payload.put("workerName", workerName);
            payload.put("workerPhone", worker.getUser() != null ? worker.getUser().getPhoneNumber() : "");
            payload.put("status", "ACCEPTED");
            payload.put("pinCode", pinCode);

            messagingTemplate.convertAndSend("/topic/booking/" + booking.getBookingId(), payload);
            messagingTemplate.convertAndSend("/topic/customer/" + customerId + "/booking", payload);
            recordNotificationDelivery(booking, booking.getUser(), worker, DeliveryChannel.WS, "/topic/booking/" + booking.getBookingId(), DeliveryStatus.SENT);
        } catch (Exception e) {
            logger.debug("WebSocket notify error: {}", e.getMessage());
        }

        // FCM
        try {
            fcmNotificationService.sendPushNotification(
                    customerId,
                    "Partner Assigned!",
                    workerName + " has accepted your booking and is preparing for your service."
            );
            recordNotificationDelivery(booking, booking.getUser(), worker, DeliveryChannel.FCM, "FCM_PUSH", DeliveryStatus.SENT);
        } catch (Exception e) {
            logger.debug("FCM notify error: {}", e.getMessage());
        }
    }

    private void notifyCustomerWorkerNotFound(Booking booking) {
        if (booking.getUser() == null) return;
        UUID customerId = booking.getUser().getUserId();

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "WORKER_NOT_FOUND");
            payload.put("bookingId", booking.getBookingId().toString());
            payload.put("status", "WORKER_NOT_FOUND");
            payload.put("message", "No partner is currently available in your area. Please try again shortly.");

            messagingTemplate.convertAndSend("/topic/booking/" + booking.getBookingId(), payload);
            messagingTemplate.convertAndSend("/topic/customer/" + customerId + "/booking", payload);
        } catch (Exception ignored) {}

        try {
            fcmNotificationService.sendPushNotification(
                    customerId,
                    "Booking Update",
                    "We are sorry, all partners in your area are currently occupied. Please try again shortly."
            );
        } catch (Exception ignored) {}
    }

    private void broadcastBookingUpdate(Booking booking, String eventType) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", eventType);
            payload.put("bookingId", booking.getBookingId().toString());
            payload.put("status", booking.getStatus().name());
            messagingTemplate.convertAndSend("/topic/booking/" + booking.getBookingId(), payload);
            if (booking.getUser() != null) {
                messagingTemplate.convertAndSend("/topic/customer/" + booking.getUser().getUserId() + "/booking", payload);
            }
        } catch (Exception ignored) {}
    }

    private void cacheTrackingAssignment(Booking booking, Worker worker, String pinCode) {
        try {
            Map<String, Object> assignment = new HashMap<>();
            assignment.put("bookingId", booking.getBookingId().toString());
            assignment.put("workerId", worker.getWorkerId().toString());
            assignment.put("userId", worker.getUser() != null ? worker.getUser().getUserId().toString() : "");
            assignment.put("customerId", booking.getUser() != null ? booking.getUser().getUserId().toString() : "");
            assignment.put("status", "ACCEPTED");
            assignment.put("pinCode", pinCode);
            assignment.put("acceptedAt", Instant.now().toString());
            if (booking.getUserLocation() != null) {
                assignment.put("customerLat", booking.getUserLocation().getY());
                assignment.put("customerLng", booking.getUserLocation().getX());
            }

            String json = objectMapper.writeValueAsString(assignment);
            redisTemplate.opsForValue().set("tracking:assignment:" + booking.getBookingId(), json, Duration.ofHours(24));
        } catch (Exception e) {
            logger.warn("Redis tracking assignment cache error: {}", e.getMessage());
        }
    }

    private void recordNotificationDelivery(Booking booking, User user, Worker worker,
                                            DeliveryChannel channel, String dest, DeliveryStatus status) {
        try {
            NotificationDelivery delivery = NotificationDelivery.builder()
                    .booking(booking)
                    .user(user)
                    .worker(worker)
                    .channel(channel)
                    .topicDestination(dest)
                    .status(status)
                    .attemptNo(1)
                    .sentAt(Instant.now())
                    .deliveredAt(Instant.now())
                    .build();
            deliveryRepository.save(delivery);
        } catch (Exception ignored) {}
    }

    private void recordDispatchAuditLog(Booking booking, UUID serviceId, double radiusMeters,
                                        int eligibleCount, Map<String, String> rejectionReasons,
                                        UUID selectedWorkerId, String kafkaStatus,
                                        String wsStatus, String fcmStatus, String assignmentStatus) {
        if (booking == null) return;

        // Structured Section 6 Diagnostics Logging (17 standard fields)
        try {
            String bookingIdStr = booking.getBookingId() != null ? booking.getBookingId().toString() : "N/A";
            String customerIdStr = booking.getUser() != null && booking.getUser().getUserId() != null ? booking.getUser().getUserId().toString() : "N/A";
            String serviceIdStr = serviceId != null ? serviceId.toString() : (booking.getService() != null && booking.getService().getServiceId() != null ? booking.getService().getServiceId().toString() : "N/A");
            String subServiceIdStr = "N/A";
            String bookingTypeStr = booking.getBookingType() != null ? booking.getBookingType().name() : "INSTANT";
            Double customerLat = booking.getUserLat();
            Double customerLng = booking.getUserLng();
            String customerPincode = booking.getServicePincode() != null ? booking.getServicePincode() : "N/A";
            String redisGeoKey = "active_workers_geo";
            int rejectedCount = rejectionReasons != null ? rejectionReasons.size() : 0;
            int redisCandidateCount = eligibleCount + rejectedCount;
            int eligibleWorkerCount = eligibleCount;
            int rejectedWorkerCount = rejectedCount;
            String workerIdStr = selectedWorkerId != null ? selectedWorkerId.toString() : "NONE";
            String primaryRejectionReason = (rejectionReasons != null && !rejectionReasons.isEmpty()) ? rejectionReasons.values().iterator().next() : "NONE";
            String dispatchStatus = eligibleCount > 0 ? "SUCCESS" : "NO_ELIGIBLE_WORKERS";
            String kafkaEventStatus = kafkaStatus != null ? kafkaStatus : "SENT";

            logger.info("[DISPATCH_DIAGNOSTICS] bookingId={} customerId={} serviceId={} subServiceId={} bookingType={} customerLatitude={} customerLongitude={} customerPincode={} redisGeoKey={} redisCandidateCount={} eligibleWorkerCount={} rejectedWorkerCount={} workerId={} rejectionReason={} dispatchStatus={} kafkaEventStatus={} assignmentStatus={}",
                    bookingIdStr, customerIdStr, serviceIdStr, subServiceIdStr, bookingTypeStr,
                    customerLat, customerLng, customerPincode,
                    redisGeoKey, redisCandidateCount, eligibleWorkerCount, rejectedWorkerCount,
                    workerIdStr, primaryRejectionReason, dispatchStatus, kafkaEventStatus, assignmentStatus);

            if (rejectionReasons != null) {
                for (Map.Entry<String, String> entry : rejectionReasons.entrySet()) {
                    logger.info("[DISPATCH_WORKER_REJECTED] bookingId={} workerId={} rejectionReason={}",
                            bookingIdStr, entry.getKey(), entry.getValue());
                }
            }
        } catch (Exception ex) {
            logger.debug("Diagnostics log error: {}", ex.getMessage());
        }

        if (auditLogRepository == null) return;
        try {
            String reasonsJson = rejectionReasons != null && !rejectionReasons.isEmpty()
                    ? objectMapper.writeValueAsString(rejectionReasons)
                    : null;

            DispatchAuditLog log = DispatchAuditLog.builder()
                    .booking(booking)
                    .serviceId(serviceId)
                    .customerLat(booking.getUserLat())
                    .customerLng(booking.getUserLng())
                    .searchRadiusMeters(radiusMeters)
                    .eligibleWorkersCount(eligibleCount)
                    .rejectedWorkerReasons(reasonsJson)
                    .selectedWorkerId(selectedWorkerId)
                    .kafkaStatus(kafkaStatus)
                    .websocketStatus(wsStatus)
                    .fcmStatus(fcmStatus)
                    .assignmentStatus(assignmentStatus)
                    .build();

            auditLogRepository.save(log);
        } catch (Exception e) {
            logger.debug("Dispatch audit log save skipped: {}", e.getMessage());
        }
    }

    public static double calculateHaversineDistance(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000; // meters
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return r * c;
    }
}
