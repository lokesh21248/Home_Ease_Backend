package com.homeease.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.dto.TrackingDto.*;
import com.homeease.backend.dto.WorkerDto.LocationUpdateRequest;
import com.homeease.backend.model.entity.Booking;
import com.homeease.backend.model.entity.User;
import com.homeease.backend.model.entity.Worker;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.TrackingSessionStatus;
import com.homeease.backend.repository.BookingRepository;
import com.homeease.backend.repository.WorkerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TrackingLifecycleTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private WorkerRepository workerRepository;

    @Mock
    private WorkerService workerService;

    private ObjectMapper objectMapper;
    private TrackingService trackingService;

    private UUID bookingId;
    private UUID workerUserId;
    private UUID customerUserId;
    private Booking sampleBooking;
    private final Map<String, String> inMemoryRedis = new HashMap<>();

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // In-memory simulation of Redis opsForValue get/set
        lenient().when(valueOperations.get(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return inMemoryRedis.get(key);
        });

        lenient().doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String value = invocation.getArgument(1);
            inMemoryRedis.put(key, value);
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any());

        lenient().doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            inMemoryRedis.remove(key);
            return true;
        }).when(redisTemplate).delete(anyString());

        trackingService = new TrackingService(
                redisTemplate,
                objectMapper,
                messagingTemplate,
                bookingRepository,
                workerRepository,
                workerService
        );

        bookingId = UUID.randomUUID();
        workerUserId = UUID.randomUUID();
        customerUserId = UUID.randomUUID();

        User customer = User.builder().userId(customerUserId).fullName("Customer One").build();
        User workerUser = User.builder().userId(workerUserId).fullName("Partner One").build();
        Worker worker = Worker.builder().workerId(UUID.randomUUID()).user(workerUser).build();

        sampleBooking = Booking.builder()
                .bookingId(bookingId)
                .user(customer)
                .worker(worker)
                .userLat(17.4485)
                .userLng(78.3748)
                .status(BookingStage.ACCEPTED)
                .build();

        lenient().when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(sampleBooking));
    }

    @Test
    @DisplayName("Tracking session lifecycle: CREATED -> ACTIVE (Travelling) -> ARRIVED (Auto-Stopped)")
    void testCompleteTrackingLifecycle() {
        // 1. Booking Accepted -> session created with trackingActive = false
        TrackingSessionDto createdSession = trackingService.createTrackingSession(sampleBooking);
        assertNotNull(createdSession);
        assertEquals(TrackingSessionStatus.CREATED, createdSession.getStatus());
        assertEquals(Boolean.FALSE, createdSession.getTrackingActive());

        // 2. Worker Starts Travel -> session becomes ACTIVE with trackingActive = true
        TrackingSessionDto activeSession = trackingService.startTravelling(bookingId, workerUserId);
        assertNotNull(activeSession);
        assertEquals(TrackingSessionStatus.ACTIVE, activeSession.getStatus());
        assertEquals(Boolean.TRUE, activeSession.getTrackingActive());

        // 3. Worker sends live GPS frame during travel -> accepted
        LocationUpdateRequest updateReq = LocationUpdateRequest.builder()
                .bookingId(bookingId.toString())
                .lat(17.4500)
                .lng(78.3800)
                .bearing(45.0)
                .speed(15.0)
                .accuracy(5.0)
                .timestamp(System.currentTimeMillis())
                .build();

        LocationUpdateResult updateResult = trackingService.updateWorkerLocation(bookingId, workerUserId, updateReq);
        assertTrue(updateResult.isSuccess());
        assertTrue(updateResult.isTrackingActive());
        assertEquals("ACTIVE", updateResult.getStatus());

        // 4. Worker Arrives at customer doorstep -> session marked ARRIVED, trackingActive = false
        TrackingSessionDto arrivedSession = trackingService.handleArrival(bookingId, workerUserId);
        assertNotNull(arrivedSession);
        assertEquals(TrackingSessionStatus.ARRIVED, arrivedSession.getStatus());
        assertEquals(Boolean.FALSE, arrivedSession.getTrackingActive());

        // Verify WebSocket events broadcast
        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/tracking/" + bookingId), any(Object.class));
        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/track/" + bookingId), any(Object.class));
    }

    @Test
    @DisplayName("Critical: Late GPS frames must be rejected with 409 Conflict once session is ARRIVED")
    void testLateGpsFrameRejectedWhenArrived() {
        // Prepare session in ARRIVED status
        trackingService.createTrackingSession(sampleBooking);
        trackingService.startTravelling(bookingId, workerUserId);
        trackingService.handleArrival(bookingId, workerUserId);

        // Late GPS update arrives after arrival
        LocationUpdateRequest lateFrame = LocationUpdateRequest.builder()
                .bookingId(bookingId.toString())
                .lat(17.4520)
                .lng(78.3820)
                .timestamp(System.currentTimeMillis())
                .build();

        LocationUpdateResult result = trackingService.updateWorkerLocation(bookingId, workerUserId, lateFrame);
        assertFalse(result.isSuccess(), "GPS update must be rejected after arrival");
        assertFalse(result.isTrackingActive());
        assertEquals("ARRIVED", result.getStatus());
        assertTrue(result.getMessage().contains("GPS uploads rejected"));
    }

    @Test
    @DisplayName("Distance and ETA API returns 0 and trackingActive = false when session is ARRIVED")
    void testDistanceAndEtaWhenArrived() {
        trackingService.createTrackingSession(sampleBooking);
        trackingService.handleArrival(bookingId, workerUserId);

        TrackingDistanceResponse response = trackingService.getDistanceAndEta(bookingId, 17.4485, 78.3748);
        assertNotNull(response);
        assertEquals(Boolean.FALSE, response.getTrackingActive());
        assertEquals(0L, (long) response.getDistanceMeters());
        assertEquals(0, (int) response.getEtaMinutes());
        assertEquals("Arrived", response.getEtaText());
    }

    @Test
    @DisplayName("Stopping tracking session cleans temporary Redis keys but preserves permanent records")
    void testStopTrackingCleansKeys() {
        trackingService.createTrackingSession(sampleBooking);
        trackingService.startTravelling(bookingId, workerUserId);

        // Store live coordinate key
        inMemoryRedis.put(TrackingService.REDIS_BOOKING_PREFIX + bookingId, "{\"lat\":17.45,\"lng\":78.38}");

        TrackingSessionDto stopped = trackingService.stopTracking(bookingId, "COMPLETED");
        assertEquals(TrackingSessionStatus.STOPPED, stopped.getStatus());
        assertEquals(Boolean.FALSE, stopped.getTrackingActive());

        // Verify temporary live coordinate key was deleted
        assertNull(inMemoryRedis.get(TrackingService.REDIS_BOOKING_PREFIX + bookingId));
    }
}
