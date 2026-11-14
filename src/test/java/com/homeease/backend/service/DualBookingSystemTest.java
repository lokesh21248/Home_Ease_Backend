package com.homeease.backend.service;

import com.homeease.backend.dto.BookingDto.*;
import com.homeease.backend.dto.WorkerDto.*;
import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.*;
import com.homeease.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class DualBookingSystemTest {

    @Mock private WorkerAvailabilityRepository availabilityRepository;
    @Mock private WorkerTimeOffRepository timeOffRepository;
    @Mock private BookingSlotRepository slotRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private WorkerRepository workerRepository;
    @Mock private WorkerServiceLinkRepository workerServiceLinkRepository;
    @Mock private UserRepository userRepository;
    @Mock private ServiceRepository serviceRepository;
    @Mock private SubServiceRepository subServiceRepository;
    @Mock private ServiceAddonRepository addonRepository;
    @Mock private BookingServiceItemRepository bookingServiceItemRepository;
    @Mock private BookingAddonItemRepository bookingAddonItemRepository;
    @Mock private CouponRepository couponRepository;
    @Mock private ServiceAreaService serviceAreaService;
    @Mock private BookingAssignmentService assignmentService;
    @Mock private BookingNotificationService notificationService;
    @Mock private BookingDomainEventService domainEventService;
    @Mock private BookingCancellationService cancellationService;
    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private UserAddressService userAddressService;

    private WorkerAvailabilityService availabilityService;
    private ScheduledBookingService scheduledBookingService;
    private InstantBookingService instantBookingService;

    private User testUser;
    private Worker testWorker;
    private ServiceEntity testService;
    private SubService testSubService;
    private UUID workerId;
    private UUID userId;
    private UUID serviceId;
    private UUID subServiceId;

/**     * The date n days from now (IST), moved forward past a Sunday. The service returns no slots on Sundays, so     * tests that need slots must not depend on which weekday the suite happens to run on (the original     * plusDays(1) failed every Saturday).     */    private static LocalDate futureNonSunday(int days) {        LocalDate d = LocalDate.now(WorkerAvailabilityService.IST_ZONE).plusDays(days);        return d.getDayOfWeek() == java.time.DayOfWeek.SUNDAY ? d.plusDays(1) : d;    }
    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        workerId = UUID.randomUUID();
        serviceId = UUID.randomUUID();
        subServiceId = UUID.randomUUID();

        testUser = User.builder()
                .userId(userId)
                .fullName("Rahul Sharma")
                .phoneNumber("9876543210")
                .role(UserRole.CUSTOMER)
                .build();

        testWorker = Worker.builder()
                .workerId(workerId)
                .user(User.builder().userId(UUID.randomUUID()).fullName("Vikram Singh").role(UserRole.WORKER).build())
                .isOnline(true)
                .isVerified(true)
                .currentLat(17.4485)
                .currentLng(78.3748)
                .build();

        testService = ServiceEntity.builder()
                .serviceId(serviceId)
                .name("Home Deep Cleaning")
                .imageUrl("https://firstdoor.com/cleaning.png")
                .isActive(true)
                .build();

        testSubService = SubService.builder()
                .subServiceId(subServiceId)
                .service(testService)
                .name("Full House Deep Cleaning")
                .basePrice(new BigDecimal("1499.00"))
                .estimatedMins(120)
                .imageUrl("https://firstdoor.com/deep.png")
                .isActive(true)
                .build();

        availabilityService = new WorkerAvailabilityService(
                availabilityRepository,
                timeOffRepository,
                slotRepository,
                bookingRepository,
                workerRepository,
                workerServiceLinkRepository
        );

        scheduledBookingService = new ScheduledBookingService(
                bookingRepository,
                userRepository,
                serviceRepository,
                subServiceRepository,
                addonRepository,
                bookingServiceItemRepository,
                bookingAddonItemRepository,
                couponRepository,
                slotRepository,
                workerRepository,
                workerServiceLinkRepository,
                serviceAreaService,
                availabilityService,
                notificationService,
                domainEventService,
                redisTemplate,
                userAddressService
        );

        instantBookingService = new InstantBookingService(
                bookingRepository,
                userRepository,
                serviceRepository,
                subServiceRepository,
                addonRepository,
                bookingServiceItemRepository,
                bookingAddonItemRepository,
                couponRepository,
                serviceAreaService,
                assignmentService,
                domainEventService,
                userAddressService
        );
    }

    @Test
    @DisplayName("Worker Availability: Dynamic Time Slots calculation excludes break times & past slots")
    void testDynamicTimeSlotsCalculation() {
        LocalDate testDate = futureNonSunday(2); // In future
        when(workerRepository.findAll()).thenReturn(List.of(testWorker));

        WorkerAvailability wa = WorkerAvailability.builder()
                .worker(testWorker)
                .dayOfWeek(testDate.getDayOfWeek().getValue())
                .isAvailable(true)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 0))
                .breakStartTime(LocalTime.of(13, 0))
                .breakEndTime(LocalTime.of(14, 0))
                .slotDurationMins(60)
                .build();

        when(availabilityRepository.findByWorker_WorkerIdAndDayOfWeek(eq(workerId), anyInt()))
                .thenReturn(Optional.of(wa));
        when(timeOffRepository.findByWorkerAndDate(eq(workerId), eq(testDate)))
                .thenReturn(Collections.emptyList());
        when(bookingRepository.countOverlappingBookings(eq(workerId), any(), any(), any()))
                .thenReturn(0L);

        List<AvailableSlotDto> slots = availabilityService.getAvailableTimeSlots(testDate, serviceId, null);

        assertNotNull(slots);
        assertFalse(slots.isEmpty());

        // Verify that the 13:00 - 14:00 slot is marked unavailable (break time)
        Optional<AvailableSlotDto> breakSlot = slots.stream()
                .filter(s -> "13:00".equals(s.getStartTime()))
                .findFirst();
        assertTrue(breakSlot.isPresent());
        assertFalse(breakSlot.get().getAvailable(), "Break time slot must not be available");

        // Verify that 10:00 - 11:00 slot is available
        Optional<AvailableSlotDto> normalSlot = slots.stream()
                .filter(s -> "10:00".equals(s.getStartTime()))
                .findFirst();
        assertTrue(normalSlot.isPresent());
        assertTrue(normalSlot.get().getAvailable(), "Normal working hour slot must be available");
    }

    @Test
    @DisplayName("Worker Availability: Overlapping booking blocks slot from being double-booked")
    void testOverlappingBookingPreventsDoubleBooking() {
        LocalDate testDate = futureNonSunday(1);
        when(workerRepository.findAll()).thenReturn(List.of(testWorker));

        WorkerAvailability wa = WorkerAvailability.builder()
                .worker(testWorker)
                .dayOfWeek(testDate.getDayOfWeek().getValue())
                .isAvailable(true)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(18, 0))
                .slotDurationMins(60)
                .build();

        when(availabilityRepository.findByWorker_WorkerIdAndDayOfWeek(eq(workerId), anyInt()))
                .thenReturn(Optional.of(wa));
        when(timeOffRepository.findByWorkerAndDate(eq(workerId), eq(testDate)))
                .thenReturn(Collections.emptyList());

        // Simulate that 10:00 - 11:00 has an active overlapping booking in database
        when(bookingRepository.countOverlappingBookings(eq(workerId), any(), any(), any()))
                .thenAnswer(inv -> {
                    Instant start = inv.getArgument(1);
                    ZonedDateTime zdt = start.atZone(WorkerAvailabilityService.IST_ZONE);
                    return (zdt.getHour() == 10) ? 1L : 0L;
                });

        List<AvailableSlotDto> slots = availabilityService.getAvailableTimeSlots(testDate, serviceId, null);

        Optional<AvailableSlotDto> slot10Am = slots.stream()
                .filter(s -> "10:00".equals(s.getStartTime()))
                .findFirst();
        assertTrue(slot10Am.isPresent());
        assertFalse(slot10Am.get().getAvailable(), "Slot with active overlapping booking must be unavailable");
    }

    @Test
    @DisplayName("Worker Availability: Worker Time Off marks entire day unavailable")
    void testWorkerTimeOffMarksDayUnavailable() {
        LocalDate testDate = futureNonSunday(3);
        when(workerRepository.findAll()).thenReturn(List.of(testWorker));

        WorkerAvailability wa = WorkerAvailability.builder()
                .worker(testWorker)
                .dayOfWeek(testDate.getDayOfWeek().getValue())
                .isAvailable(true)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(18, 0))
                .build();

        when(availabilityRepository.findByWorker_WorkerIdAndDayOfWeek(eq(workerId), anyInt()))
                .thenReturn(Optional.of(wa));

        // Worker is on leave on testDate
        when(timeOffRepository.findByWorkerAndDate(eq(workerId), eq(testDate)))
                .thenReturn(List.of(WorkerTimeOff.builder()
                        .worker(testWorker)
                        .startDate(testDate)
                        .endDate(testDate)
                        .reason("Vacation")
                        .build()));

        List<AvailableSlotDto> slots = availabilityService.getAvailableTimeSlots(testDate, serviceId, null);
        assertTrue(slots.isEmpty(), "No slots should be returned if all eligible workers are on time off");
    }

    @Test
    @DisplayName("Scheduled Booking: Creates booking with Redis lock and CONFIRMED status")
    void testCreateScheduledBooking() {
        LocalDate futureDate = futureNonSunday(2);
        ScheduledBookingRequest req = ScheduledBookingRequest.builder()
                .serviceId(serviceId)
                .subServices(List.of(SubServiceRequestItem.builder().subServiceId(subServiceId).quantity(1).build()))
                .scheduledDate(futureDate)
                .timeSlot("10:00 AM - 11:00 AM")
                .userLat(17.4485)
                .userLng(78.3748)
                .paymentMethod(PaymentMode.COD)
                .pincode("500072")
                .build();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(serviceRepository.findById(serviceId)).thenReturn(Optional.of(testService));
        when(subServiceRepository.findById(subServiceId)).thenReturn(Optional.of(testSubService));
        when(workerServiceLinkRepository.findById_SubServiceId(subServiceId)).thenReturn(
                List.of(WorkerServiceLink.builder().worker(testWorker).build()));

        when(availabilityRepository.findByWorker_WorkerIdAndDayOfWeek(eq(workerId), anyInt()))
                .thenReturn(Optional.of(WorkerAvailability.builder()
                        .worker(testWorker)
                        .dayOfWeek(futureDate.getDayOfWeek().getValue())
                        .isAvailable(true)
                        .startTime(LocalTime.of(8, 0))
                        .endTime(LocalTime.of(20, 0))
                        .build()));
        when(timeOffRepository.findByWorkerAndDate(eq(workerId), eq(futureDate))).thenReturn(Collections.emptyList());
        when(bookingRepository.countOverlappingBookings(eq(workerId), any(), any(), any())).thenReturn(0L);

        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            b.setBookingId(UUID.randomUUID());
            return b;
        });

        when(slotRepository.save(any(BookingSlot.class))).thenAnswer(inv -> {
            BookingSlot bs = inv.getArgument(0);
            bs.setSlotId(UUID.randomUUID());
            return bs;
        });

        BookingResponse response = scheduledBookingService.createScheduledBooking(userId, req, null);

        assertNotNull(response);
        assertEquals(BookingType.SCHEDULED, response.getBookingType());
        assertEquals(BookingStage.CONFIRMED, response.getStatus());
        assertNotNull(response.getPinCode());
        assertEquals(4, response.getPinCode().length());
        assertEquals(new BigDecimal("1499.00"), response.getTotalAmount());
        verify(notificationService).notifyBookingConfirmed(any(Booking.class));
    }

    @Test
    @DisplayName("Instant Booking: Creates immediate booking, computes ETA and dispatches to nearby workers")
    void testCreateInstantBooking() {
        InstantBookingRequest req = InstantBookingRequest.builder()
                .serviceId(serviceId)
                .subServices(List.of(SubServiceRequestItem.builder().subServiceId(subServiceId).quantity(1).build()))
                .userLat(17.4485)
                .userLng(78.3748)
                .paymentMethod(PaymentMode.UPI)
                .pincode("500072")
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(serviceRepository.findById(serviceId)).thenReturn(Optional.of(testService));
        when(subServiceRepository.findById(subServiceId)).thenReturn(Optional.of(testSubService));

        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            b.setBookingId(UUID.randomUUID());
            return b;
        });

        BookingResponse response = instantBookingService.createInstantBooking(userId, req, null);

        assertNotNull(response);
        assertEquals(BookingType.INSTANT, response.getBookingType());
        assertEquals(BookingStage.REQUESTED, response.getStatus());
        assertNotNull(response.getPinCode());
        assertEquals(4, response.getPinCode().length());
        verify(assignmentService).dispatchInstantBooking(any(Booking.class), eq(subServiceId));
    }
}
