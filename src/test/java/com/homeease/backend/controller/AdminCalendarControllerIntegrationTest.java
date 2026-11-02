package com.homeease.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.dto.AdminCalendarDto.*;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.BookingType;
import com.homeease.backend.model.enums.OverrideType;
import com.homeease.backend.repository.UserRepository;
import com.homeease.backend.security.JwtService;
import com.homeease.backend.service.AdminCalendarService;
import com.homeease.backend.service.BookingAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminCalendarController.class)
@AutoConfigureMockMvc(addFilters = false)
public class AdminCalendarControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private AdminCalendarService calendarService;
    @MockBean private BookingAuditService auditService;
    @MockBean private UserRepository userRepository;
    @MockBean private JwtService jwtService;

    private UUID adminId;
    private UUID bookingId;
    private UUID workerId;

    @BeforeEach
    void setUp() {
        adminId = UUID.randomUUID();
        bookingId = UUID.randomUUID();
        workerId = UUID.randomUUID();
    }

    @Test
    @DisplayName("API: GET /api/v1/admin/calendar/events - Day/Week/Month events")
    void testGetCalendarEvents() throws Exception {
        CalendarEventDto event = CalendarEventDto.builder()
                .bookingId(bookingId)
                .jobId("#HE-8492")
                .customerName("Anita Desai")
                .serviceName("Deep Cleaning")
                .bookingType(BookingType.SCHEDULED)
                .status(BookingStage.CONFIRMED)
                .startAt(Instant.now())
                .totalAmount(new BigDecimal("1299.00"))
                .build();

        when(calendarService.getCalendarEvents(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(event)));

        mockMvc.perform(get("/api/v1/admin/calendar/events")
                        .header("X-Admin-Role", "ADMIN")
                        .param("view", "month")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content[0].bookingId").value(bookingId.toString()))
                .andExpect(jsonPath("$.data.content[0].jobId").value("#HE-8492"));
    }

    @Test
    @DisplayName("API: GET /api/v1/admin/calendar/summary - Summary metrics")
    void testGetCalendarSummary() throws Exception {
        CalendarSummaryDto summary = CalendarSummaryDto.builder()
                .startDate("2026-11-01")
                .endDate("2026-11-30")
                .totalBookings(42)
                .instantBookings(18)
                .scheduledBookings(24)
                .confirmedBookings(20)
                .completedBookings(15)
                .unassignedBookings(2)
                .build();

        when(calendarService.getCalendarSummary(any(), any(), any(), any())).thenReturn(summary);

        mockMvc.perform(get("/api/v1/admin/calendar/summary")
                        .header("X-Admin-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.totalBookings").value(42))
                .andExpect(jsonPath("$.data.unassignedBookings").value(2));
    }

    @Test
    @DisplayName("API: PUT /api/v1/admin/calendar/working-hours - Save operating hours")
    void testSaveWorkingHours() throws Exception {
        WorkingHoursDto monday = WorkingHoursDto.builder()
                .dayOfWeek(1)
                .isOpen(true)
                .openTime("08:00")
                .closeTime("20:00")
                .build();

        SaveWorkingHoursRequest request = SaveWorkingHoursRequest.builder()
                .schedules(List.of(monday))
                .build();

        when(calendarService.saveWorkingHours(any(), any())).thenReturn(List.of(monday));

        mockMvc.perform(put("/api/v1/admin/calendar/working-hours")
                        .header("X-Admin-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].dayOfWeek").value(1));
    }

    @Test
    @DisplayName("API: POST /api/v1/admin/calendar/date-overrides - Add Public Holiday")
    void testCreateDateOverride() throws Exception {
        CreateDateOverrideRequest request = CreateDateOverrideRequest.builder()
                .overrideDate(LocalDate.now().plusDays(10))
                .overrideType(OverrideType.PUBLIC_HOLIDAY)
                .isClosed(true)
                .reason("Diwali Holiday")
                .build();

        DateOverrideDto responseDto = DateOverrideDto.builder()
                .id(UUID.randomUUID())
                .overrideDate(request.getOverrideDate())
                .overrideType(request.getOverrideType())
                .isClosed(true)
                .reason("Diwali Holiday")
                .build();

        when(calendarService.createDateOverride(any(), any())).thenReturn(responseDto);

        mockMvc.perform(post("/api/v1/admin/calendar/date-overrides")
                        .header("X-Admin-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.reason").value("Diwali Holiday"));
    }

    @Test
    @DisplayName("API: PUT /api/v1/admin/calendar/bookings/{id}/reschedule - Admin Reschedule")
    void testRescheduleBookingApi() throws Exception {
        RescheduleBookingRequest request = RescheduleBookingRequest.builder()
                .newScheduledAt(Instant.now().plusSeconds(86400))
                .reason("Customer requested reschedule")
                .build();

        CalendarEventDto rescheduledDto = CalendarEventDto.builder()
                .bookingId(bookingId)
                .status(BookingStage.CONFIRMED)
                .startAt(request.getNewScheduledAt())
                .build();

        when(calendarService.rescheduleBooking(eq(bookingId), any(), any())).thenReturn(rescheduledDto);

        mockMvc.perform(put("/api/v1/admin/calendar/bookings/" + bookingId + "/reschedule")
                        .header("X-Admin-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.bookingId").value(bookingId.toString()));
    }

    @Test
    @DisplayName("API: PUT /api/v1/admin/calendar/bookings/{id}/assign-worker - Admin Worker Assignment")
    void testAssignWorkerApi() throws Exception {
        AssignWorkerRequest request = AssignWorkerRequest.builder()
                .workerId(workerId)
                .reason("Manual assignment by operations team")
                .build();

        CalendarEventDto assignedDto = CalendarEventDto.builder()
                .bookingId(bookingId)
                .workerId(workerId)
                .workerName("Vikram Singh")
                .status(BookingStage.CONFIRMED)
                .build();

        when(calendarService.assignWorker(eq(bookingId), any(), any())).thenReturn(assignedDto);

        mockMvc.perform(put("/api/v1/admin/calendar/bookings/" + bookingId + "/assign-worker")
                        .header("X-Admin-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.workerId").value(workerId.toString()))
                .andExpect(jsonPath("$.data.workerName").value("Vikram Singh"));
    }
}

