package com.homeease.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.dto.BookingDto.BookingResponse;
import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.BookingStage;
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

import java.time.Instant;
import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BookingController.class)
@AutoConfigureMockMvc(addFilters = false)
public class SecureServiceStartPinTest {

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
    @MockBean private com.homeease.backend.repository.ReviewRepository reviewRepository;
    @MockBean private com.homeease.backend.service.LedgerService ledgerService;
    @MockBean private com.homeease.backend.service.FraudDetectionService fraudDetectionService;
    @MockBean private JwtService jwtService;

    private UUID bookingId;
    private UUID customerId;
    private UUID otherCustomerId;
    private UUID workerUserId;
    private UUID workerId;
    private Booking testBooking;
    private Worker testWorker;
    private User testCustomer;

    @BeforeEach
    void setUp() {
        bookingId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        otherCustomerId = UUID.randomUUID();
        workerUserId = UUID.randomUUID();
        workerId = UUID.randomUUID();

        testCustomer = User.builder()
                .userId(customerId)
                .fullName("Anita Sharma")
                .phoneNumber("9876543210")
                .build();

        testWorker = Worker.builder()
                .workerId(workerId)
                .user(User.builder().userId(workerUserId).fullName("Ram Singh").build())
                .build();

        testBooking = Booking.builder()
                .bookingId(bookingId)
                .status(BookingStage.ACCEPTED)
                .user(testCustomer)
                .worker(testWorker)
                .pinCode("****")
                .build();
    }

    @Test
    @DisplayName("Scenario 1: Customer retrieves PIN when ARRIVED -> Succeeds with 4-digit PIN")
    void testCustomerRetrievesPinWhenArrived() throws Exception {
        testBooking.setStatus(BookingStage.ARRIVED);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(testBooking));
        when(bookingOtpService.getCustomerPin(testBooking)).thenReturn("7492");

        mockMvc.perform(get("/api/bookings/" + bookingId + "/service-pin")
                        .header("X-User-Id", customerId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(bookingId.toString()))
                .andExpect(jsonPath("$.pinCode").value("7492"))
                .andExpect(jsonPath("$.status").value("ARRIVED"));
    }

    @Test
    @DisplayName("Scenario 2: Customer attempts retrieval before ARRIVED -> Rejected (400)")
    void testCustomerCannotRetrievePinBeforeArrived() throws Exception {
        testBooking.setStatus(BookingStage.ON_THE_WAY);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(testBooking));

        mockMvc.perform(get("/api/bookings/" + bookingId + "/service-pin")
                        .header("X-User-Id", customerId.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.pinCode").doesNotExist())
                .andExpect(jsonPath("$.status").value("ON_THE_WAY"));
    }

    @Test
    @DisplayName("Scenario 3: Non-owner customer attempts retrieval -> Forbidden (403)")
    void testNonOwnerCustomerCannotRetrievePin() throws Exception {
        testBooking.setStatus(BookingStage.ARRIVED);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(testBooking));

        mockMvc.perform(get("/api/bookings/" + bookingId + "/service-pin")
                        .header("X-User-Id", otherCustomerId.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Scenario 4: Worker marks arrived -> Response NEVER leaks PIN (pinCode is null)")
    void testWorkerArriveResponseOmitsPin() throws Exception {
        testBooking.setStatus(BookingStage.ARRIVED);
        when(assignmentService.handleWorkerArrival(eq(bookingId), eq(workerUserId))).thenReturn(testBooking);
        when(bookingStateMachine.mapToBookingResponse(any())).thenReturn(BookingResponse.builder()
                .bookingId(bookingId)
                .status(BookingStage.ARRIVED)
                .pinCode("7492") // simulation of map to response
                .build());

        mockMvc.perform(post("/api/bookings/" + bookingId + "/arrive")
                        .header("X-User-Id", workerUserId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARRIVED"))
                .andExpect(jsonPath("$.pinCode").doesNotExist());
    }

    @Test
    @DisplayName("Scenario 5: Worker enters correct PIN -> ARRIVED transitions to IN_PROGRESS")
    void testWorkerVerifiesCorrectPinSuccessfully() throws Exception {
        testBooking.setStatus(BookingStage.ARRIVED);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(testBooking));
        when(workerRepository.findByUser_UserId(workerUserId)).thenReturn(Optional.of(testWorker));
        when(bookingOtpService.verifyOtp(eq(testBooking), eq(workerUserId), eq("7492"))).thenReturn(true);

        ServiceSession session = ServiceSession.builder()
                .sessionId(UUID.randomUUID())
                .booking(testBooking)
                .startedAt(Instant.now())
                .status(ServiceSessionStatus.ACTIVE)
                .build();
        when(sessionService.startSession(any(), any())).thenReturn(session);

        Map<String, String> body = Map.of("pin", "7492");

        mockMvc.perform(post("/api/bookings/" + bookingId + "/verify-pin")
                        .header("X-User-Id", workerUserId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.bookingStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.sessionId").value(session.getSessionId().toString()));
    }

    @Test
    @DisplayName("Scenario 6: Worker enters incorrect PIN -> Rejected")
    void testWorkerEntersIncorrectPin() throws Exception {
        testBooking.setStatus(BookingStage.ARRIVED);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(testBooking));
        when(workerRepository.findByUser_UserId(workerUserId)).thenReturn(Optional.of(testWorker));
        doThrow(new IllegalArgumentException("Invalid verification PIN. 2 attempts remaining."))
                .when(bookingOtpService).verifyOtp(eq(testBooking), eq(workerUserId), eq("0000"));

        Map<String, String> body = Map.of("pin", "0000");

        mockMvc.perform(post("/api/bookings/" + bookingId + "/verify-pin")
                        .header("X-User-Id", workerUserId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid verification PIN. 2 attempts remaining."));
    }

    @Test
    @DisplayName("Scenario 7: Duplicate start attempt when already IN_PROGRESS -> Rejected")
    void testDuplicateStartRejected() throws Exception {
        testBooking.setStatus(BookingStage.IN_PROGRESS);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(testBooking));
        doThrow(new IllegalStateException("Service can only be started when booking status is ARRIVED."))
                .when(bookingOtpService).verifyOtp(eq(testBooking), eq(workerUserId), eq("7492"));

        Map<String, String> body = Map.of("pin", "7492");

        mockMvc.perform(post("/api/bookings/" + bookingId + "/verify-pin")
                        .header("X-User-Id", workerUserId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }
}
