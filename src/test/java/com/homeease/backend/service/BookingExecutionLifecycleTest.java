package com.homeease.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.*;
import com.homeease.backend.repository.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class BookingExecutionLifecycleTest {

        @Mock
        private WorkerRepository workerRepository;
        @Mock
        private WorkerServiceLinkRepository workerServiceLinkRepository;
        @Mock
        private BookingRepository bookingRepository;
        @Mock
        private BookingAssignmentRepository assignmentRepository;
        @Mock
        private ReviewRepository reviewRepository;
        @Mock
        private NotificationDeliveryRepository deliveryRepository;
        @Mock
        private BookingOtpRepository otpRepository;
        @Mock
        private ServiceSessionRepository sessionRepository;
        @Mock
        private PaymentRepository paymentRepository;
        @Mock
        private BookingEventRepository bookingEventRepository;
        @Mock
        private BookingServiceItemRepository bookingServiceItemRepository;
        @Mock
        private RedisTemplate<String, String> redisTemplate;
        @Mock
        private GeoOperations<String, String> geoOperations;
        @Mock
        private ValueOperations<String, String> valueOperations;
        @Mock
        private FcmNotificationService fcmNotificationService;
        @Mock
        private BookingDomainEventService domainEventService;
        @Mock
        private SimpMessagingTemplate messagingTemplate;

        private ObjectMapper objectMapper = new ObjectMapper();
        private BookingOtpService bookingOtpService;
        private ServiceSessionService sessionService;
        private BookingAssignmentService assignmentService;
        private BookingDispatchConsumer dispatchConsumer;

        private User testCustomer;
        private User testWorkerUser1;
        private User testWorkerUser2;
        private Worker testWorker1;
        private Worker testWorker2;
        private ServiceEntity testService;
        private Booking testBooking;
        private UUID bookingId;
        private UUID workerId1;
        private UUID workerId2;
        private UUID workerUserId1;
        private UUID workerUserId2;
        private UUID customerId;

        @BeforeEach
        void setUp() {
                customerId = UUID.randomUUID();
                workerUserId1 = UUID.randomUUID();
                workerUserId2 = UUID.randomUUID();
                workerId1 = UUID.randomUUID();
                workerId2 = UUID.randomUUID();
                bookingId = UUID.randomUUID();

                testService = ServiceEntity.builder()
                                .serviceId(UUID.randomUUID())
                                .name("AC Repair & Service")
                                .build();

                testBooking = Booking.builder()
                                .bookingId(bookingId)
                                .user(testCustomer)
                                .service(testService)
                                .status(BookingStage.REQUESTED)
                                .bookingType(BookingType.INSTANT)
                                .pinCode("1234")
                                .userLat(17.4480)
                                .userLng(78.3740)
                                .baseAmount(new BigDecimal("500.00"))
                                .extraAmount(BigDecimal.ZERO)
                                .discountAmount(BigDecimal.ZERO)
                                .totalAmount(new BigDecimal("500.00"))
                                .paymentStatus(TransactionState.PENDING)
                                .paymentMethod(PaymentMode.COD)
                                .recipientName("Rohan Verma")
                                .recipientPhone("9876543210")
                                .recipientAddress("Flat 401, HiTech Residency, Hyderabad")
                                .createdAt(Instant.now())
                                .build();

                bookingOtpService = new BookingOtpService(otpRepository, fcmNotificationService, messagingTemplate);
                sessionService = new ServiceSessionService(sessionRepository, bookingRepository, paymentRepository,
                                domainEventService, fcmNotificationService, messagingTemplate);
                assignmentService = new BookingAssignmentService(workerRepository, workerServiceLinkRepository,
                                bookingRepository,
                                assignmentRepository, reviewRepository, deliveryRepository, redisTemplate,
                                fcmNotificationService,
                                domainEventService, messagingTemplate, bookingOtpService, objectMapper);
                dispatchConsumer = new BookingDispatchConsumer(bookingRepository, bookingServiceItemRepository,
                                assignmentService, bookingEventRepository, objectMapper);
        }

        @Test
        @DisplayName("Lifecycle: Spatial Discovery & Candidate Filtering - ignores offline/unverified")
        void testWorkerCandidateDiscoveryFiltering() {
                Worker offlineWorker = Worker.builder()
                                .workerId(UUID.randomUUID())
                                .user(User.builder().userId(UUID.randomUUID()).build())
                                .isOnline(false)
                                .isVerified(true)
                                .build();

                Worker unverifiedWorker = Worker.builder()
                                .workerId(UUID.randomUUID())
                                .user(User.builder().userId(UUID.randomUUID()).build())
                                .isOnline(true)
                                .isVerified(false)
                                .build();

                assertTrue(assignmentService.isCandidateEligible(testWorker1, null));
                assertFalse(assignmentService.isCandidateEligible(offlineWorker, null));
                assertFalse(assignmentService.isCandidateEligible(unverifiedWorker, null));
        }

        @Test
        @DisplayName("Lifecycle: Batch Dispatch creates assignments and alerts workers")
        void testBatchDispatch() {
                when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));

                assignmentService.dispatchToBatch(testBooking, List.of(testWorker1, testWorker2), 1);

                verify(assignmentRepository, times(2)).save(any(BookingAssignment.class));
                verify(fcmNotificationService, times(2)).sendPushNotification(any(UUID.class), anyString(),
                                anyString());
                assertEquals(BookingStage.SEARCHING, testBooking.getStatus());
        }

        @Test
        @DisplayName("Lifecycle: Worker Acceptance & Atomic Assignment - Exactly one worker wins")
        void testWorkerAcceptance_SingleWinner() {
                when(bookingRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(testBooking));
                when(workerRepository.findByUser_UserId(workerUserId1)).thenReturn(Optional.of(testWorker1));
                when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));
                when(assignmentRepository.findByBooking_BookingIdAndWorker_WorkerId(bookingId, workerId1))
                                .thenReturn(Optional.of(BookingAssignment.builder().assignmentId(UUID.randomUUID())
                                                .status(AssignmentStatus.PENDING).build()));
                when(redisTemplate.opsForValue()).thenReturn(valueOperations);

                Booking accepted = assignmentService.handleWorkerAcceptance(bookingId, workerUserId1);

                assertEquals(BookingStage.ACCEPTED, accepted.getStatus());
                assertEquals(testWorker1, accepted.getWorker());
                assertNotNull(accepted.getWorkerAssignedAt());
                verify(assignmentRepository).dropOtherPendingAssignments(eq(bookingId), eq(workerId1),
                                eq(AssignmentStatus.DROPPED), any(Instant.class));

                // Simultaneous second worker attempts to accept: rejected because status is no
                // longer SEARCHING/REQUESTED
                when(bookingRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(accepted));
                assertThrows(IllegalStateException.class,
                                () -> assignmentService.handleWorkerAcceptance(bookingId, workerUserId2));
        }

        @Test
        @DisplayName("Lifecycle: Worker Arrival - Validates status, updates ARRIVED, and issues OTP to customer")
        void testWorkerArrivalAndOtpIssuance() {
                testBooking.setStatus(BookingStage.ACCEPTED);
                testBooking.setWorker(testWorker1);

                when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(testBooking));
                when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));
                when(otpRepository.findFirstByBooking_BookingIdAndStatus(eq(bookingId), eq(OtpStatus.ACTIVE)))
                                .thenReturn(Optional.empty());
                when(otpRepository.save(any(BookingOtp.class))).thenAnswer(i -> i.getArgument(0));

                Booking arrivedBooking = assignmentService.handleWorkerArrival(bookingId, workerUserId1);

                assertEquals(BookingStage.ARRIVED, arrivedBooking.getStatus());
                assertNotNull(arrivedBooking.getArrivedAt());

                // OTP was saved with SHA-256 hash and plaintext sent only to customer
                verify(otpRepository)
                                .save(argThat(otp -> otp.getHashedOtp() != null && otp.getHashedOtp().length() == 64));
                verify(fcmNotificationService).sendPushNotification(eq(customerId), contains("Service Start OTP"),
                                anyString());
        }

        @Test
        @DisplayName("Lifecycle: OTP Verification - Wrong OTP increment attempts and enforces lockout")
        void testOtpVerificationFailures() {
                testBooking.setStatus(BookingStage.ARRIVED);
                testBooking.setWorker(testWorker1);

                String secretOtp = "4821";
                String hashedOtp = bookingOtpService.hashOtp(secretOtp, bookingId);

                BookingOtp otp = BookingOtp.builder()
                                .otpId(UUID.randomUUID())
                                .booking(testBooking)
                                .hashedOtp(hashedOtp)
                                .phoneHash("phone-hash")
                                .expiresAt(Instant.now().plus(10, ChronoUnit.MINUTES))
                                .maxAttempts(3)
                                .attempts(0)
                                .status(OtpStatus.ACTIVE)
                                .build();

                when(otpRepository.findFirstByBooking_BookingIdAndStatus(bookingId, OtpStatus.ACTIVE))
                                .thenReturn(Optional.of(otp));

                // 1. Wrong OTP submitted
                assertThrows(IllegalArgumentException.class,
                                () -> bookingOtpService.verifyOtp(testBooking, workerUserId1, "9999"));
                assertEquals(1, otp.getAttempts());

                // 2. Second wrong OTP
                assertThrows(IllegalArgumentException.class,
                                () -> bookingOtpService.verifyOtp(testBooking, workerUserId1, "1111"));
                assertEquals(2, otp.getAttempts());

                // 3. Third wrong OTP triggers max attempts revocation
                assertThrows(IllegalArgumentException.class,
                                () -> bookingOtpService.verifyOtp(testBooking, workerUserId1, "0000"));
                assertEquals(OtpStatus.REVOKED, otp.getStatus());

                // 4. Unauthorized worker attempt
                assertThrows(SecurityException.class,
                                () -> bookingOtpService.verifyOtp(testBooking, workerUserId2, secretOtp));
        }

        @Test
        @DisplayName("Lifecycle: Correct OTP Verification -> Starts Atomic Service Session with backend UTC timestamp")
        void testCorrectOtpVerificationStartsServiceSession() {
                testBooking.setStatus(BookingStage.ARRIVED);
                testBooking.setWorker(testWorker1);

                String secretOtp = "7294";
                String hashedOtp = bookingOtpService.hashOtp(secretOtp, bookingId);

                BookingOtp otp = BookingOtp.builder()
                                .otpId(UUID.randomUUID())
                                .booking(testBooking)
                                .hashedOtp(hashedOtp)
                                .expiresAt(Instant.now().plus(10, ChronoUnit.MINUTES))
                                .maxAttempts(5)
                                .attempts(0)
                                .status(OtpStatus.ACTIVE)
                                .build();

                when(otpRepository.findFirstByBooking_BookingIdAndStatus(bookingId, OtpStatus.ACTIVE))
                                .thenReturn(Optional.of(otp));
                when(sessionRepository.findActiveSessionForBooking(bookingId)).thenReturn(Optional.empty());
                when(sessionRepository.save(any(ServiceSession.class))).thenAnswer(i -> {
                        ServiceSession s = i.getArgument(0);
                        s.setSessionId(UUID.randomUUID());
                        return s;
                });
                when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));

                boolean verified = bookingOtpService.verifyOtp(testBooking, workerUserId1, secretOtp);
                assertTrue(verified);
                assertEquals(OtpStatus.USED, otp.getStatus());

                ServiceSession session = sessionService.startSession(testBooking, testWorker1);
                assertNotNull(session.getSessionId());
                assertNotNull(session.getStartedAt());
                assertEquals(ServiceSessionStatus.ACTIVE, session.getStatus());
                assertEquals(BookingStage.IN_PROGRESS, testBooking.getStatus());
        }

        @Test
        @DisplayName("Lifecycle: Elapsed Time Calculation for Client Sync (no per-second DB polling)")
        void testSessionElapsedCalculation() {
                Instant startedAt = Instant.now().minus(25, ChronoUnit.MINUTES);
                ServiceSession session = ServiceSession.builder()
                                .sessionId(UUID.randomUUID())
                                .booking(testBooking)
                                .worker(testWorker1)
                                .customer(testCustomer)
                                .startedAt(startedAt)
                                .status(ServiceSessionStatus.ACTIVE)
                                .build();

                when(sessionRepository.findByBooking_BookingId(bookingId)).thenReturn(Optional.of(session));

                Map<String, Object> sessionInfo = sessionService.getSessionInfo(bookingId);
                long elapsed = (long) sessionInfo.get("elapsedSeconds");

                // Elapsed should be roughly 25 mins = 1500 seconds
                assertTrue(elapsed >= 1490 && elapsed <= 1510);
                assertEquals("ACTIVE", sessionInfo.get("status"));
        }

        @Test
        @DisplayName("Lifecycle: Service Completion, Price Calculation & Worker Payout")
        void testServiceCompletion() {
                testBooking.setStatus(BookingStage.IN_PROGRESS);
                testBooking.setWorker(testWorker1);
                testBooking.setBaseAmount(new BigDecimal("600.00"));
                testBooking.setExtraAmount(new BigDecimal("150.00"));
                testBooking.setDiscountAmount(new BigDecimal("50.00"));

                Instant startedAt = Instant.now().minus(45, ChronoUnit.MINUTES);
                ServiceSession session = ServiceSession.builder()
                                .sessionId(UUID.randomUUID())
                                .booking(testBooking)
                                .worker(testWorker1)
                                .customer(testCustomer)
                                .startedAt(startedAt)
                                .status(ServiceSessionStatus.ACTIVE)
                                .build();

                when(sessionRepository.findActiveSessionForBooking(bookingId)).thenReturn(Optional.of(session));
                when(sessionRepository.save(any(ServiceSession.class))).thenAnswer(i -> i.getArgument(0));
                when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));

                ServiceSession completedSession = sessionService.completeSession(testBooking, workerUserId1);

                assertEquals(ServiceSessionStatus.COMPLETED, completedSession.getStatus());
                assertNotNull(completedSession.getCompletedAt());
                // Final price: 600 + 150 - 50 = 700.00
                assertEquals(new BigDecimal("700.00"), completedSession.getFinalPrice());
                assertEquals(BookingStage.COMPLETED, testBooking.getStatus());
                assertEquals(TransactionState.SUCCESS, testBooking.getPaymentStatus());

                // Worker payment recorded with 15% platform commission
                verify(paymentRepository).save(argThat(p -> p.getGrossAmount().compareTo(new BigDecimal("700.00")) == 0
                                &&
                                p.getPlatformCommissionAmount().compareTo(new BigDecimal("105.00")) == 0 &&
                                p.getWorkerPayoutAmount().compareTo(new BigDecimal("595.00")) == 0));
        }

        @Test
        @DisplayName("Lifecycle: Kafka BOOKING_CREATED Event Consumption triggers spatial dispatch")
        void testKafkaDispatchConsumer() throws Exception {
                Map<String, Object> eventPayload = Map.of(
                                "eventType", "BOOKING_CREATED",
                                "bookingId", bookingId.toString(),
                                "occurredAt", Instant.now().toString());
                String json = objectMapper.writeValueAsString(eventPayload);

                ConsumerRecord<String, String> record = new ConsumerRecord<>("firstdoor.booking.events.v1", 0, 100L,
                                bookingId.toString(), json);

                when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(testBooking));
                when(bookingServiceItemRepository.findByBooking_BookingId(bookingId))
                                .thenReturn(Collections.emptyList());

                dispatchConsumer.consumeBookingEvent(record, null);

                verify(bookingEventRepository).save(any(BookingEvent.class));
        }
}
