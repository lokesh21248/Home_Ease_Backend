package com.homeease.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.exception.JobAlreadyAcceptedException;
import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.*;
import com.homeease.backend.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InstantAcceptRaceConcurrencyTest {

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private WorkerRepository workerRepository;

    @Mock
    private BookingAssignmentRepository assignmentRepository;

    @Mock
    private BookingDomainEventService domainEventService;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private FcmNotificationService fcmNotificationService;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query nativeQuery;

    private InstantAcceptService acceptService;
    private final ReentrantLock postgresAdvisoryLock = new ReentrantLock(true); // Fair lock simulating Postgres advisory lock

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        lenient().when(nativeQuery.getSingleResult()).thenReturn(1);

        // Simulate Postgres pg_advisory_xact_lock: serializes concurrent transactions for this lock key
        lenient().when(entityManager.createNativeQuery(anyString())).thenAnswer(inv -> {
            postgresAdvisoryLock.lock();
            return nativeQuery;
        });

        acceptService = new InstantAcceptService(
                bookingRepository,
                workerRepository,
                assignmentRepository,
                domainEventService,
                messagingTemplate,
                fcmNotificationService,
                redisTemplate,
                new ObjectMapper()
        );
        ReflectionTestUtils.setField(acceptService, "entityManager", entityManager);
    }

    @Test
    @DisplayName("Phase 3 Check: 20 workers x 1 booking accepting simultaneously -> exactly 1 ASSIGNED, 19 get job taken")
    void test20WorkersSimultaneousAccept_ExactlyOneWins_19GetJobTaken() throws Exception {
        int workerCount = 20;
        UUID bookingId = UUID.randomUUID();

        // 1 Shared in-memory Booking object simulating database row with atomic state transitions
        final Booking sharedBooking = Booking.builder()
                .bookingId(bookingId)
                .status(BookingStage.SEARCHING)
                .version(0)
                .build();

        // Synchronized database mock behaving like Postgres row lock (SELECT FOR UPDATE)
        when(bookingRepository.findByIdForUpdate(bookingId)).thenAnswer(inv -> {
            synchronized (sharedBooking) {
                return Optional.of(Booking.builder()
                        .bookingId(sharedBooking.getBookingId())
                        .status(sharedBooking.getStatus())
                        .worker(sharedBooking.getWorker())
                        .version(sharedBooking.getVersion())
                        .pinCode(sharedBooking.getPinCode())
                        .build());
            }
        });

        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            synchronized (sharedBooking) {
                sharedBooking.setStatus(b.getStatus());
                sharedBooking.setWorker(b.getWorker());
                sharedBooking.setVersion(b.getVersion());
                sharedBooking.setPinCode(b.getPinCode());
                return sharedBooking;
            }
        });

        // 20 distinct Workers and Users
        Map<UUID, Worker> workerMap = new ConcurrentHashMap<>();
        List<UUID> userIds = new ArrayList<>();

        for (int i = 0; i < workerCount; i++) {
            UUID userId = UUID.randomUUID();
            UUID workerId = UUID.randomUUID();
            User user = User.builder().userId(userId).fullName("Worker " + i).phoneNumber("+9198765432" + (10 + i)).build();
            Worker worker = Worker.builder().workerId(workerId).user(user).isOnline(true).isVerified(true).build();

            workerMap.put(userId, worker);
            userIds.add(userId);

            lenient().when(workerRepository.findByUser_UserId(userId)).thenReturn(Optional.of(worker));
        }

        // ── 20 Concurrent Threads Firing Simultaneously ───────────────────────
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch readyLatch = new CountDownLatch(workerCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(workerCount);

        AtomicInteger assignedCount = new AtomicInteger(0);
        AtomicInteger jobTakenCount = new AtomicInteger(0);
        List<InstantAcceptService.AcceptBookingResult> successResults = Collections.synchronizedList(new ArrayList<>());
        List<String> rejectionMessages = Collections.synchronizedList(new ArrayList<>());

        for (UUID userId : userIds) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(); // All 20 threads released at the exact same microsecond

                    InstantAcceptService.AcceptBookingResult result = acceptService.acceptBooking(bookingId, userId);
                    assignedCount.incrementAndGet();
                    successResults.add(result);
                } catch (JobAlreadyAcceptedException e) {
                    jobTakenCount.incrementAndGet();
                    rejectionMessages.add(e.getMessage());
                } catch (Exception e) {
                    fail("Unexpected exception: " + e.getMessage());
                } finally {
                    // Simulate transaction end in Postgres releasing advisory lock
                    if (postgresAdvisoryLock.isHeldByCurrentThread()) {
                        postgresAdvisoryLock.unlock();
                    }
                    finishLatch.countDown();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // FIRE!
        finishLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // ── Phase 3 Assertions ───────────────────────────────────────────────
        assertEquals(1, assignedCount.get(), "Exactly ONE worker must be ASSIGNED to the booking");
        assertEquals(19, jobTakenCount.get(), "Exactly 19 workers must receive 'job taken' (HTTP 409 Conflict)");

        // Assert winning result properties
        assertEquals(1, successResults.size());
        InstantAcceptService.AcceptBookingResult winner = successResults.get(0);
        assertTrue(winner.isSuccess());
        assertNotNull(winner.getWorkerId());
        assertNotNull(winner.getPinCode());
        assertEquals(1, winner.getVersion());

        // Assert all 19 losers received the exact 'job taken' message
        for (String msg : rejectionMessages) {
            assertTrue(msg.contains("already been accepted by another partner"),
                    "Rejection message must indicate job was already taken");
        }

        // Assert database state
        assertEquals(BookingStage.ACCEPTED, sharedBooking.getStatus());
        assertNotNull(sharedBooking.getWorker());
        assertEquals(1, sharedBooking.getVersion());

        // Verify competing assignments were dropped
        verify(assignmentRepository, times(1)).dropOtherPendingAssignments(
                eq(bookingId), eq(winner.getWorkerId()), eq(AssignmentStatus.DROPPED), any()
        );
    }
}
