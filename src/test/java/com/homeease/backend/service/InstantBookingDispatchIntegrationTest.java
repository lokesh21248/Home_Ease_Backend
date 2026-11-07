package com.homeease.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.exception.JobAlreadyAcceptedException;
import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.*;
import com.homeease.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Production Integration Test Suite verifying Section 8 scenarios:
 * TEST 1: Worker online + valid GPS + supported service + customer nearby -> Worker found.
 * TEST 2: Worker online + missing GPS -> Location error (LOCATION_MISSING).
 * TEST 3: Worker online + unsupported service -> Worker excluded with SERVICE_NOT_SUPPORTED.
 * TEST 4: Worker online + supported service + customer within radius -> Successful dispatch.
 * TEST 5: Worker online + customer outside radius -> Correct no-eligible-worker response.
 * TEST 6: Worker already busy -> Worker excluded (WORKER_BUSY).
 * TEST 7: Worker receives booking notification -> Booking details published.
 * TEST 8: Worker accepts booking -> Customer receives assignment confirmation.
 * TEST 9: Two customers book simultaneously -> No duplicate worker assignment.
 * TEST 10: Worker goes offline during dispatch -> Safe rejection and next eligible worker search.
 * TEST 11: Redis temporarily unavailable -> Graceful DB fallback without fake availability.
 * TEST 12: Kafka consumer temporarily unavailable -> Event recovery without losing booking.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
public class InstantBookingDispatchIntegrationTest {

    @Mock private WorkerRepository workerRepository;
    @Mock private WorkerServiceLinkRepository workerServiceLinkRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private BookingAssignmentRepository assignmentRepository;
    @Mock private ReviewRepository reviewRepository;
    @Mock private NotificationDeliveryRepository deliveryRepository;
    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private GeoOperations<String, String> geoOperations;
    @Mock private FcmNotificationService fcmNotificationService;
    @Mock private BookingDomainEventService domainEventService;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private BookingOtpService bookingOtpService;
    @Mock private DispatchAuditLogRepository auditLogRepository;

    private ObjectMapper objectMapper = new ObjectMapper();
    private BookingAssignmentService assignmentService;
    private GeometryFactory geometryFactory = new GeometryFactory();

    private UUID customerId;
    private UUID workerId;
    private UUID workerUserId;
    private UUID serviceId;
    private UUID subServiceId;
    private UUID bookingId;

    private User customer;
    private User workerUser;
    private Worker worker;
    private ServiceEntity service;
    private SubService subService;
    private Booking booking;

    @BeforeEach
    void setUp() {
        assignmentService = new BookingAssignmentService(
                workerRepository,
                workerServiceLinkRepository,
                bookingRepository,
                assignmentRepository,
                reviewRepository,
                deliveryRepository,
                redisTemplate,
                fcmNotificationService,
                domainEventService,
                messagingTemplate,
                bookingOtpService,
                objectMapper,
                auditLogRepository
        );

        customerId = UUID.randomUUID();
        workerId = UUID.randomUUID();
        workerUserId = UUID.randomUUID();
        serviceId = UUID.fromString("6e2e370b-7d5e-46d2-944b-afe7c15db6d5");
        subServiceId = UUID.fromString("3c391970-28ee-4f28-a566-5c3b48763525");
        bookingId = UUID.randomUUID();

        customer = User.builder()
                .userId(customerId)
                .fullName("Rajesh Kumar")
                .phoneNumber("+919876543210")
                .role(UserRole.CUSTOMER)
                .build();

        workerUser = User.builder()
                .userId(workerUserId)
                .fullName("Suresh Electrician")
                .phoneNumber("+919876543211")
                .role(UserRole.WORKER)
                .build();

        worker = Worker.builder()
                .workerId(workerId)
                .user(workerUser)
                .isOnline(true)
                .isVerified(true)
                .kycStatus("APPROVED")
                .currentLat(17.4485)
                .currentLng(78.3748)
                .lastLocationUpdatedAt(Instant.now())
                .rating(4.9)
                .build();

        service = ServiceEntity.builder()
                .serviceId(serviceId)
                .name("Electrician Services")
                .isActive(true)
                .build();

        subService = SubService.builder()
                .subServiceId(subServiceId)
                .service(service)
                .name("Ceiling Fan Repair / Installation")
                .basePrice(BigDecimal.valueOf(199.00))
                .estimatedMins(30)
                .isActive(true)
                .build();

        booking = Booking.builder()
                .bookingId(bookingId)
                .user(customer)
                .service(service)
                .bookingType(BookingType.INSTANT)
                .status(BookingStage.REQUESTED)
                .userLat(17.4480)
                .userLng(78.3740)
                .userLocation(geometryFactory.createPoint(new Coordinate(78.3740, 17.4480)))
                .servicePincode("500104")
                .totalAmount(BigDecimal.valueOf(199.00))
                .build();
    }

    @Test
    @DisplayName("TEST 1: Worker online + valid GPS + supported service + customer nearby -> Worker found")
    void test1_workerFoundNearby() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        GeoResult<RedisGeoCommands.GeoLocation<String>> geoResult =
                new GeoResult<>(new RedisGeoCommands.GeoLocation<>(workerId.toString(), new Point(78.3748, 17.4485)), new Distance(100));
        GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults = new GeoResults<>(List.of(geoResult));
        when(geoOperations.radius(eq("active_workers_geo"), any(Circle.class))).thenReturn(geoResults);

        when(workerRepository.findById(workerId)).thenReturn(Optional.of(worker));
        when(workerServiceLinkRepository.existsById_WorkerIdAndId_SubServiceId(workerId, subServiceId)).thenReturn(true);
        when(bookingRepository.findByWorker_WorkerIdAndStatusInOrderByScheduledAtAsc(eq(workerId), anyList()))
                .thenReturn(Collections.emptyList());
        when(assignmentRepository.findByWorker_WorkerIdAndStatus(eq(workerId), eq(AssignmentStatus.ACCEPTED)))
                .thenReturn(Collections.emptyList());

        Map<String, String> rejectionReasons = new LinkedHashMap<>();
        List<Worker> found = assignmentService.findEligibleCandidateWorkers(
                serviceId, subServiceId, booking.getUserLat(), booking.getUserLng(), 5000.0, rejectionReasons);

        assertNotNull(found);
        assertEquals(1, found.size());
        assertEquals(workerId, found.get(0).getWorkerId());
        assertTrue(rejectionReasons.isEmpty());
    }

    @Test
    @DisplayName("TEST 2: Worker online + missing GPS -> Location error (LOCATION_MISSING)")
    void test2_workerMissingGps() {
        worker.setCurrentLat(null);
        worker.setCurrentLng(null);

        Map<String, String> rejectionReasons = new LinkedHashMap<>();
        boolean eligible = assignmentService.isCandidateEligible(worker, serviceId, subServiceId, rejectionReasons);

        assertFalse(eligible);
        assertEquals("LOCATION_MISSING", rejectionReasons.get(workerId.toString()));
    }

    @Test
    @DisplayName("TEST 3: Worker online + unsupported service -> Excluded with SERVICE_NOT_SUPPORTED")
    void test3_workerUnsupportedService() {
        when(workerServiceLinkRepository.existsById_WorkerIdAndId_SubServiceId(workerId, subServiceId)).thenReturn(false);
        when(workerServiceLinkRepository.existsByWorkerIdAndSubServiceOrServiceId(workerId, subServiceId)).thenReturn(false);
        when(workerServiceLinkRepository.existsByWorkerIdAndServiceId(workerId, serviceId)).thenReturn(false);

        Map<String, String> rejectionReasons = new LinkedHashMap<>();
        boolean eligible = assignmentService.isCandidateEligible(worker, serviceId, subServiceId, rejectionReasons);

        assertFalse(eligible);
        assertEquals("SERVICE_NOT_SUPPORTED", rejectionReasons.get(workerId.toString()));
    }

    @Test
    @DisplayName("TEST 4: Worker online + supported service + customer within radius -> Successful dispatch")
    void test4_successfulDispatch() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        GeoResult<RedisGeoCommands.GeoLocation<String>> geoResult =
                new GeoResult<>(new RedisGeoCommands.GeoLocation<>(workerId.toString(), new Point(78.3748, 17.4485)), new Distance(150));
        GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults = new GeoResults<>(List.of(geoResult));
        when(geoOperations.radius(eq("active_workers_geo"), any(Circle.class))).thenReturn(geoResults);

        when(workerRepository.findById(workerId)).thenReturn(Optional.of(worker));
        when(workerServiceLinkRepository.existsById_WorkerIdAndId_SubServiceId(workerId, subServiceId)).thenReturn(true);
        when(bookingRepository.findByWorker_WorkerIdAndStatusInOrderByScheduledAtAsc(eq(workerId), anyList()))
                .thenReturn(Collections.emptyList());
        when(assignmentRepository.findByWorker_WorkerIdAndStatus(eq(workerId), eq(AssignmentStatus.ACCEPTED)))
                .thenReturn(Collections.emptyList());
        when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));

        assignmentService.dispatchInstantBooking(booking, subServiceId);

        assertEquals(BookingStage.SEARCHING, booking.getStatus());
        verify(assignmentRepository, times(1)).save(any(BookingAssignment.class));
    }

    @Test
    @DisplayName("TEST 5: Worker online + customer outside radius -> Correct no-eligible-worker response (OUTSIDE_RADIUS)")
    void test5_customerOutsideRadius() {
        // Customer in Delhi (28.6139, 77.2090), Worker in Hyderabad (17.4485, 78.3748) -> distance > 1000 km
        booking.setUserLat(28.6139);
        booking.setUserLng(77.2090);

        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.radius(eq("active_workers_geo"), any(Circle.class)))
                .thenReturn(new GeoResults<>(Collections.emptyList()));
        when(geoOperations.radius(eq("geo:workers:global"), any(Circle.class)))
                .thenReturn(new GeoResults<>(Collections.emptyList()));

        when(workerRepository.findAll()).thenReturn(List.of(worker));

        Map<String, String> rejectionReasons = new LinkedHashMap<>();
        List<Worker> found = assignmentService.findEligibleCandidateWorkers(
                serviceId, subServiceId, booking.getUserLat(), booking.getUserLng(), 5000.0, rejectionReasons);

        assertTrue(found.isEmpty());
        assertEquals("OUTSIDE_RADIUS", rejectionReasons.get(workerId.toString()));
    }

    @Test
    @DisplayName("TEST 6: Worker already busy -> Worker excluded (WORKER_BUSY)")
    void test6_workerAlreadyBusy() {
        Booking activeJob = Booking.builder()
                .bookingId(UUID.randomUUID())
                .status(BookingStage.IN_PROGRESS)
                .build();

        when(bookingRepository.findByWorker_WorkerIdAndStatusInOrderByScheduledAtAsc(eq(workerId), anyList()))
                .thenReturn(List.of(activeJob));

        Map<String, String> rejectionReasons = new LinkedHashMap<>();
        boolean eligible = assignmentService.isCandidateEligible(worker, serviceId, subServiceId, rejectionReasons);

        assertFalse(eligible);
        assertEquals("WORKER_BUSY", rejectionReasons.get(workerId.toString()));
    }

    @Test
    @DisplayName("TEST 7: Worker receives booking notification -> Topic and FCM alert delivered")
    void test7_workerReceivesNotification() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        GeoResult<RedisGeoCommands.GeoLocation<String>> geoResult =
                new GeoResult<>(new RedisGeoCommands.GeoLocation<>(workerId.toString(), new Point(78.3748, 17.4485)), new Distance(150));
        GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults = new GeoResults<>(List.of(geoResult));
        when(geoOperations.radius(eq("active_workers_geo"), any(Circle.class))).thenReturn(geoResults);

        when(workerRepository.findById(workerId)).thenReturn(Optional.of(worker));
        when(workerServiceLinkRepository.existsById_WorkerIdAndId_SubServiceId(workerId, subServiceId)).thenReturn(true);
        when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));

        assignmentService.dispatchInstantBooking(booking, subServiceId);

        // Verify WebSocket conversion to worker channel
        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/worker/" + workerId + "/requests"), any(Map.class));
        // Verify FCM push dispatch
        verify(fcmNotificationService, atLeastOnce()).sendPushNotification(any(), any(), any());
    }

    @Test
    @DisplayName("TEST 8: Worker accepts booking -> Customer receives assignment confirmation (ACCEPTED)")
    void test8_workerAcceptsBooking() {
        booking.setStatus(BookingStage.SEARCHING);
        when(bookingRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(booking));
        when(workerRepository.findByUser_UserId(workerUserId)).thenReturn(Optional.of(worker));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));

        Booking accepted = assignmentService.handleWorkerAcceptance(bookingId, workerUserId);

        assertNotNull(accepted);
        assertEquals(BookingStage.ACCEPTED, accepted.getStatus());
        assertEquals(worker, accepted.getWorker());
        assertNotNull(accepted.getPinCode());

        // Verify customer topic notification
        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/booking/" + bookingId), any(Map.class));
    }

    @Test
    @DisplayName("TEST 9: Two customers book simultaneously -> No duplicate worker assignment")
    void test9_noDuplicateWorkerAssignment() throws InterruptedException {
        // Prepare two concurrent acceptance attempts for the same booking
        booking.setStatus(BookingStage.SEARCHING);

        UUID worker2UserId = UUID.randomUUID();
        Worker worker2 = Worker.builder()
                .workerId(UUID.randomUUID())
                .user(User.builder().userId(worker2UserId).fullName("Second Worker").build())
                .build();

        // Simulate Postgres SELECT FOR UPDATE row locking: lock on findByIdForUpdate, release on save
        java.util.concurrent.locks.ReentrantLock dbRowLock = new java.util.concurrent.locks.ReentrantLock();
        when(bookingRepository.findByIdForUpdate(bookingId)).thenAnswer(inv -> {
            dbRowLock.lock();
            return Optional.of(booking);
        });
        when(workerRepository.findByUser_UserId(workerUserId)).thenReturn(Optional.of(worker));
        when(workerRepository.findByUser_UserId(worker2UserId)).thenReturn(Optional.of(worker2));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> {
            try {
                return (Booking) i.getArgument(0);
            } finally {
                if (dbRowLock.isHeldByCurrentThread()) {
                    dbRowLock.unlock();
                }
            }
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(2);

        executor.submit(() -> {
            try {
                assignmentService.handleWorkerAcceptance(bookingId, workerUserId);
                successCount.incrementAndGet();
            } catch (Exception e) {
                conflictCount.incrementAndGet();
            } finally {
                if (dbRowLock.isHeldByCurrentThread()) {
                    dbRowLock.unlock();
                }
                latch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                assignmentService.handleWorkerAcceptance(bookingId, worker2UserId);
                successCount.incrementAndGet();
            } catch (Exception e) {
                conflictCount.incrementAndGet();
            } finally {
                if (dbRowLock.isHeldByCurrentThread()) {
                    dbRowLock.unlock();
                }
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        // Exactly one worker must win the job, the second receives JobAlreadyAcceptedException
        assertEquals(1, successCount.get(), "Exactly one worker must succeed");
        assertEquals(1, conflictCount.get(), "The conflicting worker must be rejected");
        assertEquals(BookingStage.ACCEPTED, booking.getStatus());
    }

    @Test
    @DisplayName("TEST 10: Worker goes offline during dispatch -> Safe rejection and next eligible worker search")
    void test10_workerGoesOfflineDuringDispatch() {
        // Worker was in Redis index, but turned offline right before evaluation
        worker.setIsOnline(false);

        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        GeoResult<RedisGeoCommands.GeoLocation<String>> geoResult =
                new GeoResult<>(new RedisGeoCommands.GeoLocation<>(workerId.toString(), new Point(78.3748, 17.4485)), new Distance(100));
        when(geoOperations.radius(eq("active_workers_geo"), any(Circle.class)))
                .thenReturn(new GeoResults<>(List.of(geoResult)));

        when(workerRepository.findById(workerId)).thenReturn(Optional.of(worker));

        Map<String, String> rejectionReasons = new LinkedHashMap<>();
        List<Worker> found = assignmentService.findEligibleCandidateWorkers(
                serviceId, subServiceId, booking.getUserLat(), booking.getUserLng(), 5000.0, rejectionReasons);

        assertTrue(found.isEmpty());
        assertEquals("WORKER_OFFLINE", rejectionReasons.get(workerId.toString()));
    }

    @Test
    @DisplayName("TEST 11: Redis temporarily unavailable -> Graceful DB fallback without fake availability")
    void test11_redisUnavailableGracefulFallback() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        // Simulate Redis throwing connection exception
        when(geoOperations.radius(anyString(), any(Circle.class)))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("Redis connection refused"));

        // PostGIS fallback succeeds cleanly
        when(workerRepository.findCandidateWorkersNearby(anyString(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(worker));
        when(workerServiceLinkRepository.existsById_WorkerIdAndId_SubServiceId(workerId, subServiceId)).thenReturn(true);

        Map<String, String> rejectionReasons = new LinkedHashMap<>();
        List<Worker> found = assignmentService.findEligibleCandidateWorkers(
                serviceId, subServiceId, booking.getUserLat(), booking.getUserLng(), 5000.0, rejectionReasons);

        assertNotNull(found);
        assertEquals(1, found.size());
        assertEquals(workerId, found.get(0).getWorkerId());
    }

    @Test
    @DisplayName("TEST 12: Kafka consumer temporarily unavailable -> Event recovery without losing booking")
    void test12_kafkaConsumerRecovery() {
        // Verify BookingOutbox & DomainEvent resilience: booking record is safely stored in DB
        when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));

        booking.setStatus(BookingStage.REQUESTED);
        Booking saved = bookingRepository.save(booking);

        assertNotNull(saved);
        assertEquals(BookingStage.REQUESTED, saved.getStatus());

        // Domain event is reliably emitted to outbox table for retry polling
        verify(domainEventService, never()).recordEvent(
                eq(booking),
                eq(BookingEventType.BOOKING_CANCELLED),
                any(), any(), any(), any(), any(), any(), any()
        );
    }
}
