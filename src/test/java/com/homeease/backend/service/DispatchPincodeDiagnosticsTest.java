package com.homeease.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.BookingType;
import com.homeease.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies pincode-first worker matching, preserved structured rejection reasons in DispatchAuditLog,
 * working hours / leave exclusion, and scheduled slot pincode filtering.
 */
class DispatchPincodeDiagnosticsTest {

    private WorkerRepository workerRepository;
    private WorkerServiceLinkRepository linkRepository;
    private BookingRepository bookingRepository;
    private BookingAssignmentRepository assignmentRepository;
    private DispatchAuditLogRepository auditLogRepository;
    private WorkerAvailabilityRepository availabilityRepository;
    private WorkerTimeOffRepository timeOffRepository;
    private GeoService geoService;
    private BookingAssignmentService service;
    private ObjectMapper objectMapper;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        workerRepository = mock(WorkerRepository.class);
        linkRepository = mock(WorkerServiceLinkRepository.class);
        bookingRepository = mock(BookingRepository.class);
        assignmentRepository = mock(BookingAssignmentRepository.class);
        auditLogRepository = mock(DispatchAuditLogRepository.class);
        availabilityRepository = mock(WorkerAvailabilityRepository.class);
        timeOffRepository = mock(WorkerTimeOffRepository.class);
        geoService = mock(GeoService.class);
        objectMapper = new ObjectMapper();

        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        when(redis.opsForGeo()).thenReturn(mock(GeoOperations.class));

        service = new BookingAssignmentService(
                workerRepository,
                linkRepository,
                bookingRepository,
                assignmentRepository,
                mock(ReviewRepository.class),
                mock(NotificationDeliveryRepository.class),
                redis,
                mock(FcmNotificationService.class),
                mock(BookingDomainEventService.class),
                mock(SimpMessagingTemplate.class),
                mock(BookingOtpService.class),
                objectMapper,
                auditLogRepository
        );
        service.setGeoService(geoService);
        service.setAvailabilityRepository(availabilityRepository);
        service.setTimeOffRepository(timeOffRepository);

        when(assignmentRepository.findByBooking_BookingId(any())).thenReturn(List.of());
        when(assignmentRepository.findByBooking_BookingIdAndStatus(any(), any())).thenReturn(List.of());
        when(bookingRepository.findByWorker_WorkerIdAndStatusInOrderByScheduledAtAsc(any(), any())).thenReturn(List.of());
        when(assignmentRepository.findByWorker_WorkerIdAndStatus(any(), any())).thenReturn(List.of());
        when(timeOffRepository.findByWorkerAndDate(any(), any())).thenReturn(List.of());
        when(availabilityRepository.findByWorker_WorkerIdAndDayOfWeek(any(), anyInt())).thenReturn(Optional.empty());
    }

    private Booking sampleBooking(UUID serviceId, String pincode) {
        return Booking.builder()
                .bookingId(UUID.randomUUID())
                .service(ServiceEntity.builder().serviceId(serviceId).name("Electrician").build())
                .servicePincode(pincode)
                .bookingType(BookingType.INSTANT)
                .status(BookingStage.REQUESTED)
                .userLat(17.4485)
                .userLng(78.3748)
                .user(User.builder().userId(UUID.randomUUID()).fullName("Customer").phoneNumber("+919000000001").build())
                .build();
    }

    private Worker sampleWorker(String workingPincodes, double lat, double lng) {
        UUID wId = UUID.randomUUID();
        UUID uId = UUID.randomUUID();
        return Worker.builder()
                .workerId(wId)
                .user(User.builder().userId(uId).fullName("Worker " + wId.toString().substring(0, 4)).phoneNumber("+919000000002").build())
                .isOnline(true)
                .isVerified(true)
                .currentLat(lat)
                .currentLng(lng)
                .lastLocationUpdatedAt(Instant.now())
                .workingPincodes(workingPincodes)
                .build();
    }

    @Test
    void onlineEligibleWorkerInSamePincodeReceivesOfferFirst() {
        UUID serviceId = UUID.randomUUID();
        UUID subServiceId = UUID.randomUUID();
        Worker localWorker = sampleWorker("500081", 17.4485, 78.3748);
        Worker otherWorker = sampleWorker("500003", 17.4490, 78.3750);
        Booking booking = sampleBooking(serviceId, "500081");

        when(workerRepository.findByIsOnlineTrueAndIsVerifiedTrue()).thenReturn(List.of(localWorker, otherWorker));
        when(linkRepository.existsById_WorkerIdAndId_SubServiceId(localWorker.getWorkerId(), subServiceId)).thenReturn(true);
        when(linkRepository.existsById_WorkerIdAndId_SubServiceId(otherWorker.getWorkerId(), subServiceId)).thenReturn(true);

        service.dispatchInstantBooking(booking, subServiceId);

        ArgumentCaptor<DispatchAuditLog> auditCaptor = ArgumentCaptor.forClass(DispatchAuditLog.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        DispatchAuditLog savedAudit = auditCaptor.getValue();
        assertThat(savedAudit.getEligibleWorkersCount()).isEqualTo(1);
        assertThat(savedAudit.getSelectedWorkerId()).isEqualTo(localWorker.getWorkerId());
        assertThat(savedAudit.getAssignmentStatus()).isEqualTo("SEARCHING");
    }

    @Test
    void strategy1RejectionReasonsPreservedInAuditLogWhenStrategy2FindsNothing() throws Exception {
        UUID serviceId = UUID.randomUUID();
        UUID subServiceId = UUID.randomUUID();
        Worker wrongTradeWorker = sampleWorker("500081", 17.4485, 78.3748);
        Worker offlinePinWorker = sampleWorker("500081", 17.4485, 78.3748);
        offlinePinWorker.setIsOnline(false);
        Booking booking = sampleBooking(serviceId, "500081");

        when(workerRepository.findByIsOnlineTrueAndIsVerifiedTrue()).thenReturn(List.of(wrongTradeWorker));
        when(workerRepository.findByWorkingPincodesContaining("500081")).thenReturn(List.of(wrongTradeWorker, offlinePinWorker));
        when(linkRepository.existsById_WorkerIdAndId_SubServiceId(wrongTradeWorker.getWorkerId(), subServiceId)).thenReturn(false);

        service.dispatchInstantBooking(booking, subServiceId);

        ArgumentCaptor<DispatchAuditLog> auditCaptor = ArgumentCaptor.forClass(DispatchAuditLog.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        DispatchAuditLog log = auditCaptor.getValue();
        assertThat(log.getEligibleWorkersCount()).isEqualTo(0);
        assertThat(log.getAssignmentStatus()).isEqualTo("WORKER_NOT_FOUND");
        assertThat(log.getRejectedWorkerReasons()).isNotBlank();

        JsonNode json = objectMapper.readTree(log.getRejectedWorkerReasons());
        assertThat(json.get(wrongTradeWorker.getWorkerId().toString()).asText()).isEqualTo("SERVICE_NOT_SUPPORTED");
        assertThat(json.get(offlinePinWorker.getWorkerId().toString()).asText()).isEqualTo("WORKER_OFFLINE");
    }

    @Test
    void workerOnLeaveOrOutsideWorkingHoursIsRejectedWithStructuredReason() {
        UUID serviceId = UUID.randomUUID();
        UUID subServiceId = UUID.randomUUID();
        Worker leaveWorker = sampleWorker("500081", 17.4485, 78.3748);
        Worker closedDayWorker = sampleWorker("500081", 17.4485, 78.3748);

        when(timeOffRepository.findByWorkerAndDate(eq(leaveWorker.getWorkerId()), any(LocalDate.class)))
                .thenReturn(List.of(WorkerTimeOff.builder().timeOffId(UUID.randomUUID()).worker(leaveWorker)
                        .startDate(LocalDate.now()).endDate(LocalDate.now()).build()));

        int todayDow = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).getDayOfWeek().getValue();
        when(availabilityRepository.findByWorker_WorkerIdAndDayOfWeek(closedDayWorker.getWorkerId(), todayDow))
                .thenReturn(Optional.of(WorkerAvailability.builder()
                        .worker(closedDayWorker)
                        .dayOfWeek(todayDow)
                        .isAvailable(false)
                        .startTime(LocalTime.of(9, 0))
                        .endTime(LocalTime.of(18, 0))
                        .build()));

        Map<String, String> reasons = new LinkedHashMap<>();
        assertThat(service.isCandidateEligible(leaveWorker, serviceId, subServiceId, "500081", reasons)).isFalse();
        assertThat(reasons).containsEntry(leaveWorker.getWorkerId().toString(), "WORKER_ON_LEAVE");

        assertThat(service.isCandidateEligible(closedDayWorker, serviceId, subServiceId, "500081", reasons)).isFalse();
        assertThat(reasons).containsEntry(closedDayWorker.getWorkerId().toString(), "OUTSIDE_WORKING_HOURS");
    }

    @Test
    void explicitDifferentWorkingPincodeDoesNotFalseMatchViaCentroid() {
        Worker secunderabadWorker = sampleWorker("500003", 17.4485, 78.3748);
        when(geoService.geocodePincode("500081")).thenReturn(new double[]{17.4485, 78.3748});

        assertThat(service.matchesWorkingPincode(secunderabadWorker, "500081")).isFalse();

        Worker roamingWorkerNoPins = sampleWorker(null, 17.4485, 78.3748);
        assertThat(service.matchesWorkingPincode(roamingWorkerNoPins, "500081")).isTrue();
    }
}
