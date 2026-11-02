package com.homeease.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.dto.BookingDto.*;
import com.homeease.backend.dto.WorkerDto.WorkerBookingRequestResponse;
import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.BookingType;
import com.homeease.backend.model.enums.ServiceSessionStatus;
import com.homeease.backend.repository.BookingEventRepository;
import com.homeease.backend.repository.BookingRepository;
import com.homeease.backend.repository.WorkerRepository;
import com.homeease.backend.security.JwtService;
import com.homeease.backend.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BookingController.class)
@AutoConfigureMockMvc(addFilters = false)
public class BookingExecutionControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private BookingStateMachine bookingStateMachine;
    @MockBean private DispatchEngine dispatchEngine;
    @MockBean private InstantBookingService instantBookingService;
    @MockBean private ScheduledBookingService scheduledBookingService;
    @MockBean private WorkerAvailabilityService availabilityService;
    @MockBean private BookingAssignmentService assignmentService;
    @MockBean private BookingCancellationService cancellationService;
    @MockBean private BookingOtpService bookingOtpService;
    @MockBean private ServiceSessionService sessionService;
    @MockBean private BookingEventRepository bookingEventRepository;
    @MockBean private BookingRepository bookingRepository;
    @MockBean private com.homeease.backend.repository.BookingServiceItemRepository bookingServiceItemRepository;
    @MockBean private WorkerRepository workerRepository;
    @MockBean private JwtService jwtService;

    private UUID bookingId;
    private UUID customerId;
    private UUID workerUserId;
    private UUID workerId;
    private Booking testBooking;
    private Worker testWorker;

    @BeforeEach
    void setUp() {
        bookingId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        workerUserId = UUID.randomUUID();
        workerId = UUID.randomUUID();

        testWorker = Worker.builder()
                .workerId(workerId)
                .user(User.builder().userId(workerUserId).fullName("Ram Singh").build())
                .build();

        testBooking = Booking.builder()
                .bookingId(bookingId)
                .status(BookingStage.REQUESTED)
                .worker(testWorker)
                .build();
    }

    @Test
    @DisplayName("API: GET /api/workers/me/requests - Dispatched Leads")
    void testGetWorkerRequests() throws Exception {
        WorkerBookingRequestResponse req = WorkerBookingRequestResponse.builder()
                .bookingId(bookingId)
                .serviceName("Electrician")
                .customerName("Anita")
                .totalAmount(new BigDecimal("450.00"))
                .build();

        when(assignmentService.getPendingRequestsForWorker(workerUserId)).thenReturn(List.of(req));

        mockMvc.perform(get("/api/workers/me/requests")
                        .header("X-User-Id", workerUserId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bookingId").value(bookingId.toString()))
                .andExpect(jsonPath("$[0].serviceName").value("Electrician"));
    }

    @Test
    @DisplayName("API: POST /api/bookings/{id}/accept - Worker accepts booking")
    void testAcceptBookingApi() throws Exception {
        when(assignmentService.handleWorkerAcceptance(eq(bookingId), eq(workerUserId))).thenReturn(testBooking);
        when(bookingStateMachine.mapToBookingResponse(any())).thenReturn(BookingResponse.builder()
                .bookingId(bookingId)
                .status(BookingStage.ACCEPTED)
                .build());

        mockMvc.perform(post("/api/bookings/" + bookingId + "/accept")
                        .header("X-User-Id", workerUserId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(bookingId.toString()))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    @DisplayName("API: POST /api/bookings/{id}/arrive - Worker arrives at customer location")
    void testArriveBookingApi() throws Exception {
        testBooking.setStatus(BookingStage.ARRIVED);
        when(assignmentService.handleWorkerArrival(eq(bookingId), eq(workerUserId))).thenReturn(testBooking);
        when(bookingStateMachine.mapToBookingResponse(any())).thenReturn(BookingResponse.builder()
                .bookingId(bookingId)
                .status(BookingStage.ARRIVED)
                .build());

        mockMvc.perform(post("/api/bookings/" + bookingId + "/arrive")
                        .header("X-User-Id", workerUserId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(bookingId.toString()))
                .andExpect(jsonPath("$.status").value("ARRIVED"));
    }

    @Test
    @DisplayName("API: POST /api/bookings/{id}/verify-otp - Verifies 4-digit OTP & starts session")
    void testVerifyOtpApi() throws Exception {
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(testBooking));
        when(workerRepository.findByUser_UserId(workerUserId)).thenReturn(Optional.of(testWorker));
        when(bookingOtpService.verifyOtp(any(), eq(workerUserId), eq("4321"))).thenReturn(true);

        ServiceSession session = ServiceSession.builder()
                .sessionId(UUID.randomUUID())
                .booking(testBooking)
                .startedAt(Instant.now())
                .status(ServiceSessionStatus.ACTIVE)
                .build();
        when(sessionService.startSession(any(), any())).thenReturn(session);

        Map<String, String> body = Map.of("otp", "4321");

        mockMvc.perform(post("/api/bookings/" + bookingId + "/verify-otp")
                        .header("X-User-Id", workerUserId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.bookingStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.sessionId").value(session.getSessionId().toString()));
    }

    @Test
    @DisplayName("API: GET /api/bookings/{id}/session - Recalculates elapsed timer for mobile apps")
    void testGetSessionApi() throws Exception {
        Map<String, Object> sessionInfo = Map.of(
                "sessionId", UUID.randomUUID().toString(),
                "bookingId", bookingId.toString(),
                "status", "ACTIVE",
                "elapsedSeconds", 650,
                "startedAt", Instant.now().minusSeconds(650).toString()
        );
        when(sessionService.getSessionInfo(bookingId)).thenReturn(sessionInfo);

        mockMvc.perform(get("/api/bookings/" + bookingId + "/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(bookingId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.elapsedSeconds").value(650));
    }

    @Test
    @DisplayName("API: POST /api/bookings/{id}/complete - Completes session & records earnings")
    void testCompleteBookingApi() throws Exception {
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(testBooking));

        ServiceSession completedSession = ServiceSession.builder()
                .sessionId(UUID.randomUUID())
                .booking(testBooking)
                .status(ServiceSessionStatus.COMPLETED)
                .durationMinutes(new BigDecimal("45.00"))
                .finalPrice(new BigDecimal("650.00"))
                .completedAt(Instant.now())
                .build();
        when(sessionService.completeSession(eq(testBooking), eq(workerUserId))).thenReturn(completedSession);

        mockMvc.perform(post("/api/bookings/" + bookingId + "/complete")
                        .header("X-User-Id", workerUserId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.bookingStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.finalPrice").value(650.00));
    }
}

