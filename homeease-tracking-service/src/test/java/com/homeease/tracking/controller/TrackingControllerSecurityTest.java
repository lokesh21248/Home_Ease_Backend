package com.homeease.tracking.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.tracking.dto.LocationPayload;
import com.homeease.tracking.exception.GlobalExceptionHandler;
import com.homeease.tracking.security.JwtTokenValidator;
import com.homeease.tracking.security.TestJwt;
import com.homeease.tracking.security.TrackingAccessGuard;
import com.homeease.tracking.service.LiveTrackingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SuppressWarnings("unchecked")
class TrackingControllerSecurityTest {

    private static final String BOOKING = UUID.randomUUID().toString();
    private static final String CUSTOMER = UUID.randomUUID().toString();
    private static final String WORKER = UUID.randomUUID().toString();
    private static final String STRANGER = UUID.randomUUID().toString();

    private LiveTrackingService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(LiveTrackingService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("tracking:assignment:" + BOOKING)).thenReturn(
                "{\"customerId\":\"" + CUSTOMER + "\",\"userId\":\"" + WORKER + "\",\"workerId\":\"" + WORKER + "\"}");
        ObjectMapper om = new ObjectMapper();
        JwtTokenValidator jwt = new JwtTokenValidator(om, TestJwt.SECRET);
        TrackingAccessGuard guard = new TrackingAccessGuard(jwt, redis, om, 2, 100);
        mvc = MockMvcBuilders.standaloneSetup(new TrackingController(service, jwt, guard))
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        when(service.getLatestLocation(BOOKING)).thenReturn(
                LocationPayload.builder().bookingId(BOOKING).lat(17.46).lng(78.51).trackingActive(true).build());
        when(service.getRecentHistory(BOOKING)).thenReturn(List.of());
        when(service.getDrivingRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(Map.of("found", true));
    }

    // ───────────────────────── worker location reads ─────────────────────────

    @Test
    void customerAndWorkerCanReadLatestLocation() throws Exception {
        mvc.perform(get("/api/v1/tracking/latest/{id}", BOOKING).header("Authorization", TestJwt.bearer(CUSTOMER, "CUSTOMER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lat").value(17.46));
        mvc.perform(get("/api/v1/tracking/latest/{id}", BOOKING).header("Authorization", TestJwt.bearer(WORKER, "WORKER")))
                .andExpect(status().isOk());
    }

    @Test
    void anonymousAndUuidOnlyCallersCannotReadAnyWorkerLocation() throws Exception {
        mvc.perform(get("/api/v1/tracking/latest/{id}", BOOKING)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/tracking/latest/{id}", BOOKING).header("X-User-Id", CUSTOMER)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/tracking/latest/{id}", BOOKING).header("Authorization", "Bearer " + CUSTOMER))
                .andExpect(status().isUnauthorized());
        verify(service, never()).getLatestLocation(anyString());
    }

    @Test
    void anotherBookingsParticipantGets403AndNoLocationIsFetched() throws Exception {
        mvc.perform(get("/api/v1/tracking/latest/{id}", BOOKING).header("Authorization", TestJwt.bearer(STRANGER, "CUSTOMER")))
                .andExpect(status().isForbidden());
        verify(service, never()).getLatestLocation(anyString());
    }

    @Test
    void historyAndDistanceAreGuardedLikeLatest() throws Exception {
        mvc.perform(get("/api/v1/tracking/history/{id}", BOOKING)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/tracking/history/{id}", BOOKING).header("Authorization", TestJwt.bearer(STRANGER, "CUSTOMER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/tracking/distance").param("bookingId", BOOKING).param("customerLat", "17.5").param("customerLng", "78.5"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/tracking/distance").param("bookingId", BOOKING).param("customerLat", "17.5").param("customerLng", "78.5")
                        .header("Authorization", TestJwt.bearer(STRANGER, "CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    // ───────────────────────── operational endpoints ─────────────────────────

    @Test
    void opsEndpointsAreAdminOnly() throws Exception {
        mvc.perform(post("/api/v1/tracking/stop/{id}", BOOKING)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/tracking/stop/{id}", BOOKING).header("Authorization", TestJwt.bearer(CUSTOMER, "CUSTOMER")))
                .andExpect(status().isForbidden());
        verify(service, never()).stopTracking(anyString(), anyString());

        mvc.perform(post("/api/v1/tracking/stop/{id}", BOOKING).header("Authorization", TestJwt.bearer(STRANGER, "ADMIN")))
                .andExpect(status().isOk());
        verify(service).stopTracking(BOOKING, "COMPLETED");

        mvc.perform(get("/api/v1/tracking/stats")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/tracking/stats").header("Authorization", TestJwt.bearer(CUSTOMER, "CUSTOMER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/tracking/nearby").param("lat", "17.4").param("lng", "78.4"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/tracking/nearby").param("lat", "17.4").param("lng", "78.4")
                        .header("Authorization", TestJwt.bearer(CUSTOMER, "CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    // ───────────────────────── GPS ingestion identity ─────────────────────────

    @Test
    void gpsIngestRequiresAVerifiedJwtAndNeverTrustsXUserId() throws Exception {
        String body = "{\"lat\":17.45,\"lng\":78.38,\"accuracy\":5.0,\"timestamp\":" + System.currentTimeMillis() + "}";
        org.springframework.http.MediaType json = org.springframework.http.MediaType.APPLICATION_JSON;

        // the spoof: knowing the assigned partner's UUID is not an identity
        mvc.perform(post("/api/v1/tracking/{id}/location", BOOKING).header("X-User-Id", WORKER)
                .contentType(json).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/tracking/partner/location").header("X-User-Id", WORKER)
                .contentType(json).content("{\"bookingId\":\"" + BOOKING + "\",\"lat\":17.45,\"lng\":78.38}"))
                .andExpect(status().isUnauthorized());
        // a forged / invalid token plus the header used to fall through to the header
        mvc.perform(post("/api/v1/tracking/{id}/location", BOOKING).header("Authorization", "Bearer not.a.jwt")
                .header("X-User-Id", WORKER).contentType(json).content(body)).andExpect(status().isUnauthorized());
        // a bare-UUID bearer
        mvc.perform(post("/api/v1/tracking/{id}/location", BOOKING).header("Authorization", "Bearer " + WORKER)
                .contentType(json).content(body)).andExpect(status().isUnauthorized());
        verify(service, never()).processLocationUpdate(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        // a signed partner token is accepted and its identity (not any header) is what the service checks
        when(service.processLocationUpdate(org.mockito.ArgumentMatchers.eq(BOOKING), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(WORKER)))
                .thenReturn(LocationPayload.builder().bookingId(BOOKING).lat(17.45).lng(78.38).build());
        mvc.perform(post("/api/v1/tracking/{id}/location", BOOKING).header("Authorization", TestJwt.bearer(WORKER, "WORKER"))
                .header("X-User-Id", STRANGER).contentType(json).content(body)).andExpect(status().isOk());
        verify(service).processLocationUpdate(org.mockito.ArgumentMatchers.eq(BOOKING), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(WORKER));
    }

    @Test
    void onlyTheAssignedPartnerOrAnAdminCanStopTracking() throws Exception {
        mvc.perform(post("/api/v1/tracking/{id}/stop", BOOKING).header("Authorization", TestJwt.bearer(CUSTOMER, "CUSTOMER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/tracking/{id}/stop", BOOKING).header("Authorization", TestJwt.bearer(STRANGER, "WORKER")))
                .andExpect(status().isForbidden());
        verify(service, never()).stopTracking(anyString(), anyString());
        mvc.perform(post("/api/v1/tracking/{id}/stop", BOOKING).param("reason", "ARRIVED")
                        .header("Authorization", TestJwt.bearer(WORKER, "WORKER")))
                .andExpect(status().isOk());
        verify(service).stopTracking(BOOKING, "ARRIVED");
    }

    @Test
    void noOpStartStubIsGone() throws Exception {
        mvc.perform(post("/api/v1/tracking/{id}/start", BOOKING).header("Authorization", TestJwt.bearer(WORKER, "WORKER")))
                .andExpect(status().is4xxClientError());
    }

    // ───────────────────────── paid-API coordinate proxy ─────────────────────────

    @Test
    void anonymousCallersCannotSpendTheRoutingCredential() throws Exception {
        mvc.perform(get("/api/v1/tracking/route").param("startLat", "17.4").param("startLng", "78.3")
                        .param("destLat", "17.5").param("destLng", "78.4"))
                .andExpect(status().isUnauthorized());
        verify(service, never()).getDrivingRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void workerNavigationStillWorksForJwtAndForUuidSessionsButIsRateCapped() throws Exception {
        mvc.perform(get("/api/v1/tracking/route").param("startLat", "17.4").param("startLng", "78.3")
                        .param("destLat", "17.5").param("destLng", "78.4")
                        .header("Authorization", TestJwt.bearer(WORKER, "WORKER")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/tracking/route").param("startLat", "17.4").param("startLng", "78.3")
                        .param("destLat", "17.5").param("destLng", "78.4")
                        .header("Authorization", TestJwt.bearer(WORKER, "WORKER")))
                .andExpect(status().isOk());
        // per-identity cap (2/min in this test) reached
        mvc.perform(get("/api/v1/tracking/route").param("startLat", "17.4").param("startLng", "78.3")
                        .param("destLat", "17.5").param("destLng", "78.4")
                        .header("Authorization", TestJwt.bearer(WORKER, "WORKER")))
                .andExpect(status().isTooManyRequests());

        // worker app UUID-session fallback keeps working (another identity, own budget)
        mvc.perform(get("/api/v1/tracking/directions").param("startLat", "17.4").param("startLng", "78.3")
                        .param("destLat", "17.5").param("destLng", "78.4")
                        .header("X-User-Id", UUID.randomUUID().toString()))
                .andExpect(status().isOk());
    }

    @Test
    void postRouteValidatesNumbersInsteadOfThrowingA500() throws Exception {
        mvc.perform(post("/api/v1/tracking/route").contentType("application/json")
                        .content("{\"startLat\":\"x\",\"startLng\":78.3,\"destLat\":17.5,\"destLng\":78.4}")
                        .header("Authorization", TestJwt.bearer(WORKER, "WORKER")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void clientDestinationBookingRouteEndpointNoLongerExists() throws Exception {
        mvc.perform(get("/api/v1/tracking/{id}/route", BOOKING).param("destLat", "1").param("destLng", "2")
                        .header("Authorization", TestJwt.bearer(CUSTOMER, "CUSTOMER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void emptyOrMalformedLocationBodyReturns401Or400Never500() throws Exception {
        mvc.perform(post("/api/v1/tracking/{id}/location", BOOKING)
                        .contentType("application/json"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/tracking/{id}/location", BOOKING)
                        .header("Authorization", TestJwt.bearer(WORKER, "WORKER"))
                        .contentType("application/json"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/tracking/{id}/location", BOOKING)
                        .header("Authorization", TestJwt.bearer(WORKER, "WORKER"))
                        .contentType("application/json")
                        .content("{not-json"))
                .andExpect(status().isBadRequest());
    }
}
