package com.homeease.tracking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.tracking.config.TrackingStompSecurityInterceptor;
import com.homeease.tracking.controller.TrackingController;
import com.homeease.tracking.controller.TrackingSocketHandler;
import com.homeease.tracking.dto.LocationPayload;
import com.homeease.tracking.exception.GlobalExceptionHandler;
import com.homeease.tracking.exception.TrackingAuthorizationUnavailableException;
import com.homeease.tracking.exception.TrackingNotActiveException;
import com.homeease.tracking.exception.TrackingPersistenceUnavailableException;
import com.homeease.tracking.security.JwtTokenValidator;
import com.homeease.tracking.security.TrackingAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class LiveTrackingReliabilityAndAuthTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private GeoOperations<String, String> geoOps;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private JwtTokenValidator jwtValidator;

    @Mock
    private TrackingAccessGuard accessGuard;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private LiveTrackingService service;
    private MockMvc mockMvc;

    private static final String WORKER_USER_ID = "11111111-1111-4111-8111-111111111111";
    private static final String OTHER_WORKER_ID = "22222222-2222-4222-8222-222222222222";
    private String bookingId;

    @BeforeEach
    void setUp() {
        bookingId = UUID.randomUUID().toString();
        service = new LiveTrackingService(redisTemplate, objectMapper, messagingTemplate);
        ReflectionTestUtils.setField(service, "requireAssignment", true);
        ReflectionTestUtils.setField(service, "maxAccuracyMeters", 50.0);
        ReflectionTestUtils.setField(service, "windowSeconds", 60);
        ReflectionTestUtils.setField(service, "maxUpdates", 30);
        ReflectionTestUtils.setField(service, "movementThresholdMeters", 15.0);

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(redisTemplate.opsForGeo()).thenReturn(geoOps);

        TrackingController controller = new TrackingController(service, jwtValidator, accessGuard);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private LocationPayload validPayload(long ts) {
        LocationPayload p = new LocationPayload();
        p.setBookingId(bookingId);
        p.setLat(17.4401);
        p.setLng(78.3489);
        p.setBearing(90.0);
        p.setSpeed(7.5);
        p.setAccuracy(8.0);
        p.setTimestamp(ts);
        return p;
    }

    private String onTheWayAssignmentJson(String userId, String workerId) {
        return String.format(
                "{\"bookingId\":\"%s\",\"status\":\"ON_THE_WAY\",\"trackingActive\":true,\"userId\":\"%s\",\"workerId\":\"%s\"}",
                bookingId, userId, workerId);
    }

    @Test
    void assignmentRedisErrorFailsClosedWith503AndNeverBroadcasts() {
        when(valueOps.get("tracking:assignment:" + bookingId))
                .thenThrow(new QueryTimeoutException("Upstash TLS timeout"));

        assertThrows(TrackingAuthorizationUnavailableException.class,
                () -> service.processLocationUpdate(bookingId, validPayload(System.currentTimeMillis()), WORKER_USER_ID));

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        verify(redisTemplate, never()).convertAndSend(eq("ws:broadcast:tracking"), anyString());
        verify(valueOps, never()).set(startsWith("tracking:booking:"), anyString(), any(Duration.class));
    }

    @Test
    void malformedAssignmentJsonFailsClosedWithSecurityExceptionAndNeverBroadcasts() {
        when(valueOps.get("tracking:assignment:" + bookingId)).thenReturn("{not-valid-json");

        assertThrows(SecurityException.class,
                () -> service.processLocationUpdate(bookingId, validPayload(System.currentTimeMillis()), WORKER_USER_ID));

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        verify(redisTemplate, never()).convertAndSend(eq("ws:broadcast:tracking"), anyString());
    }

    @Test
    void assignmentMissingWorkerIdentityFailsClosedAndNeverBroadcasts() {
        String missingWorkerJson = String.format(
                "{\"bookingId\":\"%s\",\"status\":\"ON_THE_WAY\",\"trackingActive\":true}",
                bookingId);
        when(valueOps.get("tracking:assignment:" + bookingId)).thenReturn(missingWorkerJson);

        assertThrows(SecurityException.class,
                () -> service.processLocationUpdate(bookingId, validPayload(System.currentTimeMillis()), WORKER_USER_ID));

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        verify(redisTemplate, never()).convertAndSend(eq("ws:broadcast:tracking"), anyString());
    }

    @Test
    void acceptedStatusBeforeOnTheWayRejectedAs409NotActiveAndNeverBroadcasts() throws Exception {
        String acceptedJson = String.format(
                "{\"bookingId\":\"%s\",\"status\":\"ACCEPTED\",\"trackingActive\":false,\"userId\":\"%s\"}",
                bookingId, WORKER_USER_ID);
        when(valueOps.get("tracking:assignment:" + bookingId)).thenReturn(acceptedJson);
        when(accessGuard.requireVerifiedJwt("Bearer worker-jwt"))
                .thenReturn(new TrackingAccessGuard.Caller(WORKER_USER_ID, "PARTNER", true));

        assertThrows(TrackingNotActiveException.class,
                () -> service.processLocationUpdate(bookingId, validPayload(System.currentTimeMillis()), WORKER_USER_ID));

        mockMvc.perform(post("/api/v1/tracking/partner/location")
                        .header("Authorization", "Bearer worker-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validPayload(System.currentTimeMillis()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRACKING_NOT_ACTIVE"))
                .andExpect(jsonPath("$.trackingActive").value(false))
                .andExpect(jsonPath("$.retryable").value(false));

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void terminalBookingStatusesRejectedWith410AndEvicted() throws Exception {
        for (String status : new String[]{"ARRIVED", "IN_PROGRESS", "COMPLETED", "CANCELLED"}) {
            String bId = UUID.randomUUID().toString();
            String json = String.format(
                    "{\"bookingId\":\"%s\",\"status\":\"%s\",\"trackingActive\":false,\"userId\":\"%s\"}",
                    bId, status, WORKER_USER_ID);
            when(valueOps.get("tracking:assignment:" + bId)).thenReturn(json);

            LocationPayload p = validPayload(System.currentTimeMillis());
            p.setBookingId(bId);
            assertThrows(IllegalStateException.class,
                    () -> service.processLocationUpdate(bId, p, WORKER_USER_ID));
        }
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void persistsBothRedisKeysAndSequenceStrictlyBeforeWebSocketAndPubSubAndScrubsSpoofedWorkerId() {
        when(valueOps.get("tracking:assignment:" + bookingId))
                .thenReturn(onTheWayAssignmentJson(WORKER_USER_ID, "worker-entity-1"));
        LocationPayload payload = validPayload(System.currentTimeMillis());
        payload.setWorkerId(OTHER_WORKER_ID); // client spoofing attempt
        payload.setPartnerId(OTHER_WORKER_ID);

        LocationPayload saved = service.processLocationUpdate(bookingId, payload, WORKER_USER_ID);

        assertEquals(WORKER_USER_ID, saved.getWorkerId());
        assertEquals(WORKER_USER_ID, saved.getPartnerId());
        assertEquals(1L, saved.getSequenceNumber());
        assertNull(saved.getEtaMinutes());
        assertNull(saved.getDistanceKm());

        InOrder order = inOrder(valueOps, messagingTemplate, redisTemplate);
        order.verify(valueOps).set(eq("tracking:booking:" + bookingId), anyString(), eq(Duration.ofHours(2)));
        order.verify(valueOps).set(eq("homeease:tracking:" + bookingId + ":location"), anyString(), eq(Duration.ofSeconds(120)));
        order.verify(valueOps).set(eq("tracking:seq:" + bookingId), eq("1"), eq(Duration.ofHours(2)));
        order.verify(messagingTemplate).convertAndSend(eq("/topic/tracking/" + bookingId), eq(saved));
        order.verify(redisTemplate).convertAndSend(eq("ws:broadcast:tracking"), anyString());
    }

    @Test
    void redisWriteOutageFailsClosedWith503NeverBroadcastsAndDoesNotPoisonRetryDedupCache() throws Exception {
        when(valueOps.get("tracking:assignment:" + bookingId))
                .thenReturn(onTheWayAssignmentJson(WORKER_USER_ID, WORKER_USER_ID));
        doThrow(new QueryTimeoutException("Upstash write timeout"))
                .when(valueOps).set(eq("tracking:booking:" + bookingId), anyString(), any(Duration.class));

        long ts = System.currentTimeMillis();
        assertThrows(TrackingPersistenceUnavailableException.class,
                () -> service.processLocationUpdate(bookingId, validPayload(ts), WORKER_USER_ID));

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        verify(redisTemplate, never()).convertAndSend(eq("ws:broadcast:tracking"), anyString());
        assertNull(service.getLatestLocation(bookingId),
                "Unpersisted GPS update must never leak into L1 cache on Redis failure");

        // Recover Redis and resend the exact same (bookingId, lat, lng, ts) GPS fix: must succeed!
        doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
        LocationPayload recovered = service.processLocationUpdate(bookingId, validPayload(ts), WORKER_USER_ID);
        assertEquals(WORKER_USER_ID, recovered.getWorkerId());
        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/tracking/" + bookingId), eq(recovered));
    }

    @Test
    void transientRedisWriteFailureRecoversWithinBoundedRetries() {
        when(valueOps.get("tracking:assignment:" + bookingId))
                .thenReturn(onTheWayAssignmentJson(WORKER_USER_ID, WORKER_USER_ID));
        doThrow(new QueryTimeoutException("transient glitch"))
                .doNothing()
                .when(valueOps).set(eq("tracking:booking:" + bookingId), anyString(), any(Duration.class));

        LocationPayload saved = service.processLocationUpdate(
                bookingId, validPayload(System.currentTimeMillis()), WORKER_USER_ID);

        assertNotNull(saved);
        assertEquals(1L, saved.getSequenceNumber());
        verify(valueOps, times(2)).set(eq("tracking:booking:" + bookingId), anyString(), any(Duration.class));
        verify(messagingTemplate).convertAndSend(eq("/topic/tracking/" + bookingId), eq(saved));
    }

    @Test
    void controllerReturnsStructured503ForRedisPersistenceUnavailable() throws Exception {
        when(valueOps.get("tracking:assignment:" + bookingId))
                .thenReturn(onTheWayAssignmentJson(WORKER_USER_ID, WORKER_USER_ID));
        doThrow(new QueryTimeoutException("Upstash offline"))
                .when(valueOps).set(eq("homeease:tracking:" + bookingId + ":location"), anyString(), any(Duration.class));
        when(accessGuard.requireVerifiedJwt("Bearer worker-jwt"))
                .thenReturn(new TrackingAccessGuard.Caller(WORKER_USER_ID, "PARTNER", true));

        mockMvc.perform(post("/api/v1/tracking/partner/location")
                        .header("Authorization", "Bearer worker-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validPayload(System.currentTimeMillis()))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TRACKING_PERSISTENCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void monotonicSequenceHonorsExistingRedisFloorOnColdStartAndRejectsOutOfOrderTimestamp() {
        when(valueOps.get("tracking:assignment:" + bookingId))
                .thenReturn(onTheWayAssignmentJson(WORKER_USER_ID, WORKER_USER_ID));
        when(valueOps.get("tracking:seq:" + bookingId)).thenReturn("42");

        long baseTs = System.currentTimeMillis();
        LocationPayload first = service.processLocationUpdate(bookingId, validPayload(baseTs), WORKER_USER_ID);
        assertEquals(43L, first.getSequenceNumber());

        // Older timestamp is rejected without bumping sequence or broadcasting
        LocationPayload stale = service.processLocationUpdate(bookingId, validPayload(baseTs - 1000), WORKER_USER_ID);
        assertEquals(43L, stale.getSequenceNumber());
        verify(messagingTemplate, times(1)).convertAndSend(eq("/topic/tracking/" + bookingId), any(LocationPayload.class));
    }

    @Test
    void stompSocketHandlerRejectsUnverifiedXUserIdSession() {
        when(valueOps.get("tracking:assignment:" + bookingId))
                .thenReturn(onTheWayAssignmentJson(WORKER_USER_ID, WORKER_USER_ID));
        TrackingSocketHandler handler = new TrackingSocketHandler(service, messagingTemplate);

        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create();
        Map<String, Object> sessionAttrs = new HashMap<>();
        // Deliberately omit ATTR_VERIFIED = true
        accessor.setSessionAttributes(sessionAttrs);

        LocationPayload spoofed = validPayload(System.currentTimeMillis());
        spoofed.setWorkerId(WORKER_USER_ID);

        assertThrows(SecurityException.class,
                () -> handler.streamLocation(bookingId, spoofed, () -> WORKER_USER_ID, accessor));

        // Re-verify with ATTR_VERIFIED = Boolean.TRUE works cleanly
        sessionAttrs.put(TrackingStompSecurityInterceptor.ATTR_VERIFIED, Boolean.TRUE);
        handler.streamLocation(bookingId, validPayload(System.currentTimeMillis() + 1000), () -> WORKER_USER_ID, accessor);
        verify(messagingTemplate, times(1)).convertAndSend(eq("/topic/tracking/" + bookingId), any(LocationPayload.class));
    }
}
