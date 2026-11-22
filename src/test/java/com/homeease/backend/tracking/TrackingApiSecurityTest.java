package com.homeease.backend.tracking;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.homeease.backend.controller.TrackingController;
import com.homeease.backend.exception.GlobalExceptionHandler;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.UserRole;
import com.homeease.backend.repository.BookingLocationHistoryRepository;
import com.homeease.backend.routing.BookingRouteService;
import com.homeease.backend.security.JwtService;
import com.homeease.backend.security.StrictJwtTestAccess;
import com.homeease.backend.security.UserPrincipal;
import com.homeease.backend.service.BookingAssignmentService;
import com.homeease.backend.service.BookingOtpService;
import com.homeease.backend.service.TrackingService;
import com.homeease.backend.tracking.TrackingBookingLoader.BookingFacts;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * HTTP-level authorization of the core tracking API: every endpoint requires a VERIFIED JWT identity and booking
 * participation. Covers cross-customer location leakage, the legacy bare-UUID identity, role misuse and the
 * emergency rollback switch.
 */
class TrackingApiSecurityTest {

    static final long NOW = 1_800_000_000_000L;

    final UUID bookingId = UUID.randomUUID();
    final UUID customerId = UUID.randomUUID();
    final UUID workerUserId = UUID.randomUUID();
    final UUID workerEntityId = UUID.randomUUID();

    final TrackingBookingLoader loader = mock(TrackingBookingLoader.class);
    final LatestFixReader fixReader = mock(LatestFixReader.class);
    final DeviceStatusStore deviceStatus = mock(DeviceStatusStore.class);
    final BookingOtpService otpService = mock(BookingOtpService.class);
    final BookingLocationHistoryRepository historyRepo = mock(BookingLocationHistoryRepository.class);
    final TrackingService trackingService = mock(TrackingService.class);
    final BookingAssignmentService assignmentService = mock(BookingAssignmentService.class);

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        TrackingSnapshotService snapshots = new TrackingSnapshotService(loader, fixReader, deviceStatus, otpService,
                historyRepo, new TrackingPhaseResolver(), Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        TrackingController controller = new TrackingController(trackingService, mock(JwtService.class),
                mock(BookingRouteService.class), snapshots, assignmentService, deviceStatus);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();

        when(loader.load(bookingId)).thenReturn(Optional.of(new BookingFacts(bookingId, BookingStage.ON_THE_WAY,
                customerId, workerUserId, workerEntityId, "Ravi", "+919000000000", null, null, 0L, "Electrician",
                17.4486, 78.3908)));
        when(fixReader.read(bookingId)).thenReturn(Optional.of(new LatestFixReader.LatestFix(
                17.4560, 78.3908, NOW - 3_000, NOW - 3_000, 6.0, 9.0, 180.0, true)));
        when(deviceStatus.read(bookingId)).thenReturn(TrackingPhaseResolver.DeviceGpsState.OK);
        when(historyRepo.findForBooking(any(), any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        StrictJwtTestAccess.setStrict(true);
    }

    private static RequestPostProcessor as(UUID userId, UserRole role, boolean jwtVerified) {
        List<SimpleGrantedAuthority> auth = List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
        UserPrincipal p = UserPrincipal.builder().userId(userId).role(role).phoneNumber("").authorities(auth)
                .jwtVerified(jwtVerified).build();
        return req -> {
            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(new UsernamePasswordAuthenticationToken(p, null, auth));
            SecurityContextHolder.setContext(ctx);
            return req;
        };
    }

    @Test
    @DisplayName("no identity -> 401 JWT_REQUIRED on every tracking endpoint")
    void unauthenticated() throws Exception {
        for (String path : List.of("/api/v1/tracking/" + bookingId, "/api/v1/tracking/" + bookingId + "/history",
                "/api/v1/tracking/latest/" + bookingId, "/api/tracking/" + bookingId + "/location",
                "/api/v1/tracking/session/" + bookingId)) {
            SecurityContextHolder.clearContext();
            mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("JWT_REQUIRED"));
        }
        mvc.perform(post("/api/v1/tracking/" + bookingId + "/stop"))
                .andExpect(status().isUnauthorized());
        verify(trackingService, never()).stopTracking(any(), any());
    }

    @Test
    @DisplayName("legacy bare-UUID identity (not a verified JWT) is refused, even for the real customer")
    void legacyUuidIdentityRefused() throws Exception {
        mvc.perform(get("/api/v1/tracking/" + bookingId).with(as(customerId, UserRole.CUSTOMER, false)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("JWT_REQUIRED"));
    }

    @Test
    @DisplayName("cross-customer leakage: another signed-in customer gets 403 and no coordinates")
    void otherCustomerForbidden() throws Exception {
        mvc.perform(get("/api/v1/tracking/" + bookingId).with(as(UUID.randomUUID(), UserRole.CUSTOMER, true)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_BOOKING_PARTICIPANT"))
                .andExpect(jsonPath("$.workerLocation").doesNotExist());
        mvc.perform(get("/api/v1/tracking/latest/" + bookingId).with(as(UUID.randomUUID(), UserRole.CUSTOMER, true)))
                .andExpect(status().isForbidden());
        verify(trackingService, never()).getLatestLocation(any());
    }

    @Test
    @DisplayName("the booking's customer gets the live snapshot (phase, live flag, partner card with phone)")
    void customerSnapshot() throws Exception {
        mvc.perform(get("/api/v1/tracking/" + bookingId).with(as(customerId, UserRole.CUSTOMER, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("PARTNER_EN_ROUTE"))
                .andExpect(jsonPath("$.live").value(true))
                .andExpect(jsonPath("$.workerLocation.lat").value(17.456))
                .andExpect(jsonPath("$.worker.phone").value("+919000000000"))
                .andExpect(jsonPath("$.worker.rating").doesNotExist())   // 0 reviews -> no invented rating
                .andExpect(jsonPath("$.pinAvailable").value(false));
    }

    @Test
    @DisplayName("the partner sees the snapshot without the customer-only fields")
    void partnerSnapshot() throws Exception {
        mvc.perform(get("/api/v1/tracking/" + bookingId).with(as(workerUserId, UserRole.WORKER, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pinAvailable").doesNotExist())
                .andExpect(jsonPath("$.worker.phone").doesNotExist());
    }

    @Test
    @DisplayName("device status: partner may report, the customer may not")
    void deviceStatusRoles() throws Exception {
        mvc.perform(post("/api/v1/tracking/" + bookingId + "/device-status").with(as(customerId, UserRole.CUSTOMER, true))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"PERMISSION_DENIED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_NOT_ALLOWED"));
        mvc.perform(post("/api/v1/tracking/" + bookingId + "/device-status").with(as(workerUserId, UserRole.WORKER, true))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"permission_denied\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceGpsState").value("PERMISSION_DENIED"));
        mvc.perform(post("/api/v1/tracking/" + bookingId + "/device-status").with(as(workerUserId, UserRole.WORKER, true))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"ALL_GOOD\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("stop: only the assigned partner, only known reasons; the customer cannot end tracking")
    void stopRules() throws Exception {
        mvc.perform(post("/api/v1/tracking/" + bookingId + "/stop?reason=ARRIVED").with(as(customerId, UserRole.CUSTOMER, true)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/tracking/" + bookingId + "/stop?reason=HACKED").with(as(workerUserId, UserRole.WORKER, true)))
                .andExpect(status().isBadRequest());
        verify(trackingService, never()).stopTracking(any(), any());
    }

    @Test
    @DisplayName("STRICT_JWT_TRACKING=false (emergency rollback) accepts the legacy identity again")
    void rollbackSwitch() throws Exception {
        StrictJwtTestAccess.setStrict(false);
        mvc.perform(get("/api/v1/tracking/" + bookingId).with(as(customerId, UserRole.CUSTOMER, false)))
                .andExpect(status().isOk());
    }
}
