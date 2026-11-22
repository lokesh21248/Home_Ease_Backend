package com.homeease.backend.tracking;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.homeease.backend.controller.TrackingController;
import com.homeease.backend.exception.GlobalExceptionHandler;
import com.homeease.backend.model.enums.UserRole;
import com.homeease.backend.routing.BookingRouteService;
import com.homeease.backend.security.JwtService;
import com.homeease.backend.security.UserPrincipal;
import com.homeease.backend.service.TrackingService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Wiring tests for the simplified TrackingController (3-arg constructor).
 * Full snapshot/security tests live in TrackingSnapshotService layer.
 */
class TrackingApiSecurityTest {

    final UUID bookingId = UUID.randomUUID();
    final UUID customerId = UUID.randomUUID();
    final UUID workerUserId = UUID.randomUUID();

    final TrackingService trackingService = mock(TrackingService.class);

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        TrackingController controller = new TrackingController(
                trackingService,
                mock(JwtService.class),
                mock(BookingRouteService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private static RequestPostProcessor as(UUID userId, UserRole role) {
        List<SimpleGrantedAuthority> auth = List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
        UserPrincipal p = UserPrincipal.builder()
                .userId(userId).role(role).phoneNumber("")
                .authorities(auth).jwtVerified(true).build();
        return req -> {
            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(new UsernamePasswordAuthenticationToken(p, null, auth));
            SecurityContextHolder.setContext(ctx);
            return req;
        };
    }

    @Test
    @DisplayName("stop endpoint: delegates to trackingService with the given reason")
    void stopDelegatesToService() throws Exception {
        mvc.perform(post("/api/v1/tracking/" + bookingId + "/stop?reason=ARRIVED")
                        .with(as(workerUserId, UserRole.WORKER)))
                .andExpect(status().isOk());
        verify(trackingService).stopTracking(bookingId, "ARRIVED");
    }

    @Test
    @DisplayName("session endpoint returns 404 when service returns null")
    void sessionNotFound() throws Exception {
        when(trackingService.getSession(bookingId)).thenReturn(null);
        mvc.perform(get("/api/v1/tracking/session/" + bookingId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("getLatestLocation delegates to trackingService")
    void latestLocationDelegates() throws Exception {
        when(trackingService.getLatestLocation(bookingId)).thenReturn(null);
        mvc.perform(get("/api/tracking/" + bookingId + "/location"))
                .andExpect(status().isOk());
        verify(trackingService).getLatestLocation(bookingId);
    }
}
