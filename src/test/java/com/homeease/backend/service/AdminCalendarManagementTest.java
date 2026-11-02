package com.homeease.backend.service;

import com.homeease.backend.dto.AdminCalendarDto.*;
import com.homeease.backend.dto.WorkerDto.DayScheduleDto;
import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.*;
import com.homeease.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AdminCalendarManagementTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private WorkerRepository workerRepository;
    @Mock private ServiceRepository serviceRepository;
    @Mock private WorkerServiceLinkRepository workerServiceLinkRepository;
    @Mock private BusinessWorkingHoursRepository workingHoursRepository;
    @Mock private CalendarDateOverrideRepository overrideRepository;
    @Mock private BookingSettingsRepository bookingSettingsRepository;
    @Mock private BookingSlotRepository slotRepository;
    @Mock private UserRepository userRepository;
    @Mock private WorkerAvailabilityService availabilityService;
    @Mock private BookingNotificationService notificationService;
    @Mock private BookingAuditService auditService;

    private AdminCalendarService calendarService;

    private UUID adminId;
    private UUID bookingId;
    private UUID workerId;
    private UUID serviceId;
    private User testAdmin;
    private Worker testWorker;
    private ServiceEntity testService;
    private Booking testBooking;

    @BeforeEach
    void setUp() {
        calendarService = new AdminCalendarService(
                bookingRepository,
                workerRepository,
                serviceRepository,
                workerServiceLinkRepository,
                workingHoursRepository,
                overrideRepository,
                bookingSettingsRepository,
                slotRepository,
                userRepository,
                availabilityService,
                notificationService,
                auditService
        );

        adminId = UUID.randomUUID();
        bookingId = UUID.randomUUID();
        workerId = UUID.randomUUID();
        serviceId = UUID.randomUUID();

        testAdmin = User.builder()
                .userId(adminId)
                .fullName("Admin Superuser")
                .role(UserRole.ADMIN)
                .build();

        testWorker = Worker.builder()
                .workerId(workerId)
                .user(User.builder().userId(UUID.randomUUID()).fullName("Karan Patel").role(UserRole.WORKER).build())
                .isOnline(true)
                .isVerified(true)
                .build();

        testService = ServiceEntity.builder()
                .serviceId(serviceId)
                .name("AC Repair & Maintenance")
                .build();

        testBooking = Booking.builder()
                .bookingId(bookingId)
                .service(testService)
                .worker(testWorker)
                .user(User.builder().userId(UUID.randomUUID()).fullName("Aarav Mehta").build())
                .bookingType(BookingType.SCHEDULED)
                .status(BookingStage.CONFIRMED)
                .scheduledAt(Instant.now().plus(Duration.ofDays(1)))
                .scheduledEndAt(Instant.now().plus(Duration.ofDays(1)).plus(Duration.ofHours(1)))
                .totalAmount(new BigDecimal("799.00"))
                .pinCode("500081")
                .build();
    }

    @Test
    @DisplayName("Admin: Save and Fetch Business Working Hours")
    void testSaveAndGetWorkingHours() {
        WorkingHoursDto monday = WorkingHoursDto.builder()
                .dayOfWeek(1)
                .isOpen(true)
                .openTime("09:00")
                .closeTime("19:00")
                .breakStartTime("13:00")
                .breakEndTime("14:00")
                .build();

        SaveWorkingHoursRequest request = SaveWorkingHoursRequest.builder()
                .serviceId(null)
                .schedules(List.of(monday))
                .build();

        when(workingHoursRepository.findByServiceIsNullAndDayOfWeek(1))
                .thenReturn(Optional.empty());

        List<WorkingHoursDto> result = calendarService.saveWorkingHours(request, adminId);

        assertNotNull(result);
        verify(workingHoursRepository, atLeastOnce()).save(any(BusinessWorkingHours.class));
        verify(auditService).recordLog(any(), eq("WORKING_HOURS"), anyString(), eq(adminId), eq("UPDATE_WORKING_HOURS"), any(), any(), anyString());
        verify(availabilityService).invalidateCalendarCache();
    }

    @Test
    @DisplayName("Admin: Copy working hours from one weekday to others")
    void testCopyWorkingHours() {
        BusinessWorkingHours mondayHours = BusinessWorkingHours.builder()
                .dayOfWeek(1)
                .isOpen(true)
                .openTime(LocalTime.of(8, 30))
                .closeTime(LocalTime.of(18, 30))
                .breakStartTime(LocalTime.of(13, 0))
                .breakEndTime(LocalTime.of(14, 0))
                .build();

        when(workingHoursRepository.findByServiceIsNullAndDayOfWeek(1))
                .thenReturn(Optional.of(mondayHours));

        CopyWorkingHoursRequest request = CopyWorkingHoursRequest.builder()
                .sourceDayOfWeek(1)
                .targetDaysOfWeek(List.of(2, 3, 4, 5))
                .build();

        List<WorkingHoursDto> result = calendarService.copyWorkingHours(request, adminId);

        assertNotNull(result);
        verify(workingHoursRepository, times(4)).save(any(BusinessWorkingHours.class));
        verify(auditService).recordLog(any(), eq("WORKING_HOURS"), anyString(), eq(adminId), eq("COPY_WORKING_HOURS"), any(), any(), anyString());
    }

    @Test
    @DisplayName("Admin: Create, update and delete Date Overrides (Public Holiday)")
    void testCreateAndUpdateDateOverride() {
        LocalDate holidayDate = LocalDate.now().plusDays(5);

        CreateDateOverrideRequest createReq = CreateDateOverrideRequest.builder()
                .overrideDate(holidayDate)
                .overrideType(OverrideType.PUBLIC_HOLIDAY)
                .isClosed(true)
                .reason("Independence Day")
                .build();

        CalendarDateOverride createdEntity = CalendarDateOverride.builder()
                .id(UUID.randomUUID())
                .overrideDate(holidayDate)
                .overrideType(OverrideType.PUBLIC_HOLIDAY)
                .isClosed(true)
                .reason("Independence Day")
                .build();

        when(overrideRepository.save(any(CalendarDateOverride.class))).thenReturn(createdEntity);

        DateOverrideDto dto = calendarService.createDateOverride(createReq, adminId);

        assertNotNull(dto);
        assertEquals(holidayDate, dto.getOverrideDate());
        assertEquals(OverrideType.PUBLIC_HOLIDAY, dto.getOverrideType());
        verify(auditService).recordLog(any(), eq("CALENDAR_OVERRIDE"), anyString(), eq(adminId), eq("CREATE_DATE_OVERRIDE"), any(), any(), anyString());

        // Now test delete (reopen date)
        when(overrideRepository.findById(createdEntity.getId())).thenReturn(Optional.of(createdEntity));

        calendarService.deleteDateOverride(createdEntity.getId(), adminId);
        verify(overrideRepository).delete(createdEntity);
        verify(auditService).recordLog(any(), eq("CALENDAR_OVERRIDE"), eq(createdEntity.getId().toString()), eq(adminId), eq("DELETE_DATE_OVERRIDE"), any(), any(), anyString());
    }

    @Test
    @DisplayName("Admin: Reschedule Booking with Audit Log & Party Notifications")
    void testRescheduleBooking_Success() {
        Instant newTime = Instant.now().plus(Duration.ofDays(2));
        RescheduleBookingRequest request = RescheduleBookingRequest.builder()
                .newScheduledAt(newTime)
                .reason("Customer requested evening slot")
                .build();

        when(bookingRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(testBooking));
        when(availabilityService.getEffectiveSettings(any())).thenReturn(
                BookingSettings.builder().slotDurationMins(60).build()
        );
        when(availabilityService.getEffectiveDateOverride(any(), any())).thenReturn(null);
        when(availabilityService.getEffectiveWorkingHours(any(), anyInt())).thenReturn(
                BusinessWorkingHours.builder().isOpen(true).build()
        );
        when(bookingRepository.countOverlappingBookings(eq(workerId), any(), any(), any())).thenReturn(0L);
        when(bookingRepository.save(any(Booking.class))).thenReturn(testBooking);

        CalendarEventDto rescheduled = calendarService.rescheduleBooking(bookingId, request, adminId);

        assertNotNull(rescheduled);
        verify(auditService).recordLog(eq(testBooking), eq("BOOKING"), eq(bookingId.toString()), eq(adminId), eq("RESCHEDULE_BOOKING"), any(), any(), eq("Customer requested evening slot"));
        verify(notificationService).notifyBookingRescheduled(eq(testBooking), any(), eq("Customer requested evening slot"));
        verify(availabilityService).invalidateCalendarCache();
    }

    @Test
    @DisplayName("Admin: Reschedule prevents double-booking conflict on target worker")
    void testRescheduleBooking_PreventDoubleBooking() {
        Instant newTime = Instant.now().plus(Duration.ofDays(2));
        RescheduleBookingRequest request = RescheduleBookingRequest.builder()
                .newScheduledAt(newTime)
                .reason("Move job")
                .build();

        when(bookingRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(testBooking));
        when(availabilityService.getEffectiveSettings(any())).thenReturn(
                BookingSettings.builder().slotDurationMins(60).build()
        );
        when(availabilityService.getEffectiveDateOverride(any(), any())).thenReturn(null);
        when(availabilityService.getEffectiveWorkingHours(any(), anyInt())).thenReturn(
                BusinessWorkingHours.builder().isOpen(true).build()
        );
        // Simulate overlapping booking exists
        when(bookingRepository.countOverlappingBookings(eq(workerId), any(), any(), any())).thenReturn(2L);

        assertThrows(IllegalStateException.class, () -> calendarService.rescheduleBooking(bookingId, request, adminId));
    }

    @Test
    @DisplayName("Admin: Assign Worker to Unassigned Booking with validation")
    void testAssignWorker_Success() {
        Booking unassigned = Booking.builder()
                .bookingId(bookingId)
                .service(testService)
                .worker(null)
                .user(User.builder().userId(UUID.randomUUID()).fullName("Pooja Nair").build())
                .status(BookingStage.REQUESTED)
                .scheduledAt(Instant.now().plus(Duration.ofDays(1)))
                .totalAmount(new BigDecimal("999.00"))
                .build();

        when(bookingRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(unassigned));
        when(workerRepository.findById(workerId)).thenReturn(Optional.of(testWorker));
        when(bookingRepository.countOverlappingBookings(eq(workerId), any(), any(), any())).thenReturn(0L);
        when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));

        AssignWorkerRequest request = AssignWorkerRequest.builder()
                .workerId(workerId)
                .reason("Manual dispatch assignment")
                .build();

        CalendarEventDto assigned = calendarService.assignWorker(bookingId, request, adminId);

        assertNotNull(assigned);
        assertEquals(workerId, assigned.getWorkerId());
        assertEquals(BookingStage.CONFIRMED, assigned.getStatus());
        verify(auditService).recordLog(any(), eq("BOOKING"), eq(bookingId.toString()), eq(adminId), eq("ASSIGN_WORKER"), any(), eq(workerId.toString()), eq("Manual dispatch assignment"));
        verify(notificationService).notifyWorkerReassigned(any(), isNull(), eq("Manual dispatch assignment"));
    }

    @Test
    @DisplayName("Admin: Calendar Summary calculates counts across all stages")
    void testCalendarSummary() {
        List<Booking> list = List.of(
                Booking.builder().bookingType(BookingType.INSTANT).status(BookingStage.CONFIRMED).build(),
                Booking.builder().bookingType(BookingType.SCHEDULED).status(BookingStage.CONFIRMED).build(),
                Booking.builder().bookingType(BookingType.SCHEDULED).status(BookingStage.PENDING).build(),
                Booking.builder().bookingType(BookingType.INSTANT).status(BookingStage.COMPLETED).build(),
                Booking.builder().bookingType(BookingType.SCHEDULED).status(BookingStage.CANCELLED).build()
        );

        when(bookingRepository.findAll(any(Specification.class))).thenReturn(list);

        CalendarSummaryDto summary = calendarService.getCalendarSummary(LocalDate.now(), LocalDate.now().plusDays(7), null, null);

        assertNotNull(summary);
        assertEquals(5, summary.getTotalBookings());
        assertEquals(2, summary.getInstantBookings());
        assertEquals(3, summary.getScheduledBookings());
        assertEquals(1, summary.getPendingBookings());
        assertEquals(2, summary.getConfirmedBookings());
        assertEquals(1, summary.getCompletedBookings());
        assertEquals(1, summary.getCancelledBookings());
    }
}

