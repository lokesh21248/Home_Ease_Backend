package com.homeease.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.homeease.backend.dto.BookingRouteResponse;
import com.homeease.backend.dto.BookingRouteResponse.GeoPoint;
import com.homeease.backend.exception.GlobalExceptionHandler;
import com.homeease.backend.exception.RoutingException;
import com.homeease.backend.exception.RoutingException.Code;
import com.homeease.backend.model.entity.User;
import com.homeease.backend.model.enums.UserRole;
import com.homeease.backend.routing.BookingRouteService;
import com.homeease.backend.routing.RouteRefreshReason;
import com.homeease.backend.security.JwtService;
import com.homeease.backend.service.TrackingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TrackingRouteControllerTest {

    private static final String SECRET = "controller-test-secret-0123456789-abcdefghijklmnopqrstuvwxyz";
    private static final UUID BOOKING = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private JwtService jwt;
    private BookingRouteService routeService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        jwt = new JwtService(mapper, SECRET);
        routeService = mock(BookingRouteService.class);
        TrackingController controller = new TrackingController(mock(TrackingService.class), jwt, routeService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    private String tokenFor(UUID userId, UserRole role) {
        return "Bearer " + jwt.generateToken(User.builder().userId(userId).role(role)
                .fullName("Test").phoneNumber("+910000000000").build());
    }

    private BookingRouteResponse sample() {
        return new BookingRouteResponse(BOOKING, "ON_THE_WAY", true, "google-routes-v2",
                "_p~iF~ps|U_ulLnnqC_mqNvxq`@", "ENCODED_POLYLINE_1E5", 2412, 754, 690L,
                new GeoPoint(17.46, 78.51, 1_800_000_000_000L), new GeoPoint(17.5014, 78.5390, null),
                Instant.ofEpochMilli(1_800_000_000_500L), 1_800_000_000_500L, false, 20);
    }

    @Test
    void validCustomerJwtGetsTheDocumentedContract() throws Exception {
        when(routeService.getRoute(eq(BOOKING), eq(CUSTOMER), eq(false), any())).thenReturn(sample());

        mvc.perform(get("/api/v1/tracking/{id}/route", BOOKING)
                        .header("Authorization", tokenFor(CUSTOMER, UserRole.CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.bookingId").value(BOOKING.toString()))
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.polyline").value("_p~iF~ps|U_ulLnnqC_mqNvxq`@"))
                .andExpect(jsonPath("$.polylineEncoding").value("ENCODED_POLYLINE_1E5"))
                .andExpect(jsonPath("$.distanceMeters").value(2412))
                .andExpect(jsonPath("$.durationSeconds").value(754))
                .andExpect(jsonPath("$.destination.lat").value(17.5014))
                .andExpect(jsonPath("$.destination.lng").value(78.5390))
                .andExpect(jsonPath("$.origin.recordedAtEpochMs").value(1_800_000_000_000L))
                .andExpect(jsonPath("$.calculatedAtEpochMs").value(1_800_000_000_500L))
                .andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.refreshAfterSeconds").value(20))
                // nothing from the provider's raw response or any credential may leak
                .andExpect(jsonPath("$.directions").doesNotExist())
                .andExpect(jsonPath("$.apiKey").doesNotExist());
    }

    @Test
    void reasonQueryParameterIsParsedAndUnknownValuesFallBackToPeriodic() throws Exception {
        when(routeService.getRoute(any(), any(), eq(false), any())).thenReturn(sample());
        String auth = tokenFor(CUSTOMER, UserRole.CUSTOMER);

        mvc.perform(get("/api/v1/tracking/{id}/route", BOOKING).param("reason", "off_route")
                .header("Authorization", auth)).andExpect(status().isOk());
        verify(routeService).getRoute(BOOKING, CUSTOMER, false, RouteRefreshReason.OFF_ROUTE);

        mvc.perform(get("/api/v1/tracking/{id}/route", BOOKING).param("reason", "bogus")
                .header("Authorization", auth)).andExpect(status().isOk());
        verify(routeService).getRoute(BOOKING, CUSTOMER, false, RouteRefreshReason.PERIODIC);
    }

    @Test
    void adminRoleClaimIsPassedThrough() throws Exception {
        UUID admin = UUID.randomUUID();
        when(routeService.getRoute(any(), any(), eq(true), any())).thenReturn(sample());

        mvc.perform(get("/api/v1/tracking/{id}/route", BOOKING)
                        .header("Authorization", tokenFor(admin, UserRole.ADMIN)))
                .andExpect(status().isOk());
        verify(routeService).getRoute(BOOKING, admin, true, RouteRefreshReason.PERIODIC);
    }

    @Test
    void clientSuppliedCoordinatesAreIgnoredByConstruction() throws Exception {
        when(routeService.getRoute(any(), any(), eq(false), any())).thenReturn(sample());

        mvc.perform(get("/api/v1/tracking/{id}/route", BOOKING)
                        .param("destLat", "1.0").param("destLng", "2.0")
                        .param("startLat", "3.0").param("startLng", "4.0")
                        .header("Authorization", tokenFor(CUSTOMER, UserRole.CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.destination.lat").value(17.5014));
        // The service signature has no coordinate parameters at all.
        verify(routeService).getRoute(BOOKING, CUSTOMER, false, RouteRefreshReason.PERIODIC);
    }

    // ───────────────────────── identity must be a verified JWT ─────────────────────────

    @Test
    void missingAuthorizationIsRejected() throws Exception {
        mvc.perform(get("/api/v1/tracking/{id}/route", BOOKING))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("JWT_REQUIRED"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(routeService, never()).getRoute(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    void legacyBareUuidBearerAndXUserIdImpersonationAreNotHonoured() throws Exception {
        // These two header shapes authenticate against the rest of the API through JwtAuthenticationFilter's
        // "DB-verified fallback". They must NOT be enough to read a booking route.
        mvc.perform(get("/api/v1/tracking/{id}/route", BOOKING)
                        .header("Authorization", "Bearer " + CUSTOMER))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("JWT_REQUIRED"));

        mvc.perform(get("/api/v1/tracking/{id}/route", BOOKING)
                        .header("X-User-Id", CUSTOMER.toString()))
                .andExpect(status().isUnauthorized());

        verify(routeService, never()).getRoute(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    void tokenSignedWithAnotherSecretOrTamperedPayloadIsRejected() throws Exception {
        JwtService attacker = new JwtService(mapper, "attacker-secret-0123456789-abcdefghijklmnopqrstuvwxyz");
        String forged = "Bearer " + attacker.generateToken(
                User.builder().userId(CUSTOMER).role(UserRole.ADMIN).fullName("x").phoneNumber("1").build());

        mvc.perform(get("/api/v1/tracking/{id}/route", BOOKING).header("Authorization", forged))
                .andExpect(status().isUnauthorized());

        // Flip a character in a legitimately signed token's payload segment.
        String good = tokenFor(CUSTOMER, UserRole.CUSTOMER);
        String[] parts = good.split("\\.");
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AA" + "." + parts[2];
        mvc.perform(get("/api/v1/tracking/{id}/route", BOOKING).header("Authorization", tampered))
                .andExpect(status().isUnauthorized());
    }

    // ───────────────────────── documented error mapping ─────────────────────────

    @Test
    void nonParticipantGets403WithStableCode() throws Exception {
        when(routeService.getRoute(any(), any(), eq(false), any())).thenThrow(
                new RoutingException(Code.NOT_BOOKING_PARTICIPANT, "You are not allowed to view this booking's route."));

        mvc.perform(get("/api/v1/tracking/{id}/route", BOOKING)
                        .header("Authorization", tokenFor(UUID.randomUUID(), UserRole.CUSTOMER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_BOOKING_PARTICIPANT"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void providerRateLimitSurfacesRetryAfterHeaderAndBody() throws Exception {
        when(routeService.getRoute(any(), any(), eq(false), any())).thenThrow(
                new RoutingException(Code.ROUTING_RATE_LIMITED, "Road routing is busy.", 30));

        mvc.perform(get("/api/v1/tracking/{id}/route", BOOKING)
                        .header("Authorization", tokenFor(CUSTOMER, UserRole.CUSTOMER)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.code").value("ROUTING_RATE_LIMITED"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.retryAfterSeconds").value(30));
    }

    @Test
    void everyErrorCodeMapsToItsDocumentedStatus() throws Exception {
        Object[][] table = {
                {Code.TRACKING_NOT_ACTIVE, 409}, {Code.WORKER_NOT_ASSIGNED, 409},
                {Code.WORKER_LOCATION_UNAVAILABLE, 409}, {Code.WORKER_LOCATION_STALE, 409},
                {Code.DESTINATION_UNAVAILABLE, 422}, {Code.BOOKING_NOT_FOUND, 404}, {Code.NO_ROUTE_FOUND, 404},
                {Code.ROUTING_NOT_CONFIGURED, 503}, {Code.ROUTING_PROVIDER_ERROR, 502}, {Code.ROUTING_TIMEOUT, 504},
        };
        for (Object[] row : table) {
            Code code = (Code) row[0];
            // doThrow(): re-stubbing with when(mock.call()) would invoke the previous (throwing) stub.
            org.mockito.Mockito.doThrow(new RoutingException(code, "m"))
                    .when(routeService).getRoute(any(), any(), eq(false), any());
            mvc.perform(get("/api/v1/tracking/{id}/route", BOOKING)
                            .header("Authorization", tokenFor(CUSTOMER, UserRole.CUSTOMER)))
                    .andExpect(status().is((int) row[1]))
                    .andExpect(jsonPath("$.code").value(code.name()));
        }
    }

    @Test
    void malformedBookingIdIsA400NotAServerError() throws Exception {
        mvc.perform(get("/api/v1/tracking/{id}/route", "not-a-uuid")
                        .header("Authorization", tokenFor(CUSTOMER, UserRole.CUSTOMER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_UUID_FORMAT"));
    }
}
