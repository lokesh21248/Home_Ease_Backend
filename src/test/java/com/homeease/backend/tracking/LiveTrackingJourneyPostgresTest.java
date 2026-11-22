package com.homeease.backend.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.homeease.backend.dto.TrackingDto.LocationUpdateResult;
import com.homeease.backend.dto.WorkerDto.LocationUpdateRequest;
import com.homeease.backend.exception.TrackingApiException;
import com.homeease.backend.model.entity.Booking;
import com.homeease.backend.model.entity.ServiceEntity;
import com.homeease.backend.model.entity.User;
import com.homeease.backend.model.entity.Worker;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.BookingType;
import com.homeease.backend.model.enums.PaymentMode;
import com.homeease.backend.model.enums.UserRole;
import com.homeease.backend.repository.BookingRepository;
import com.homeease.backend.repository.ServiceRepository;
import com.homeease.backend.repository.UserRepository;
import com.homeease.backend.repository.WorkerRepository;
import com.homeease.backend.routing.BookingRouteContextLoader;
import com.homeease.backend.security.VerifiedCaller;
import com.homeease.backend.service.BookingAssignmentService;
import com.homeease.backend.service.BookingDomainEventService;
import com.homeease.backend.service.BookingOtpService;
import com.homeease.backend.service.FcmNotificationService;
import com.homeease.backend.service.ServiceSessionService;
import com.homeease.backend.service.TrackingService;
import com.homeease.backend.service.WorkerPresenceService;
import com.homeease.backend.service.WorkerService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * END-TO-END live tracking journey on a REAL PostgreSQL (zonky embedded, throw-away), driving the real services:
 * TrackingService (GPS ingest + validation), ArrivalDetectionService (geofence), BookingAssignmentService (state
 * transitions), BookingOtpService (PIN), ServiceSessionService (start), TrackingSnapshotService (phase / live /
 * PIN availability), LocationHistoryRecorder (PostgreSQL breadcrumbs).
 *
 * <p>Replaced: Redis (an in-memory map with the same get/set/delete semantics), STOMP and FCM (recorded mocks).
 * The road route itself is covered by BookingRouteLifecycleScenarioTest against a fake Routes API.</p>
 *
 * <p>Scenario: partner starts travelling 900 m north of the customer's Madhapur address, drives in at ~45 km/h,
 * slows and stops at the door. Expect EN_ROUTE -> NEARBY -> automatic ARRIVED (PIN issued, customer can see it,
 * later GPS rejected) -> PIN verified -> SERVICE_STARTED, with sampled breadcrumbs in PostgreSQL and no access for a
 * different customer or a different partner.</p>
 */
@SpringBootTest(
        classes = LiveTrackingJourneyPostgresTest.TestConfig.class,
        properties = {
                "spring.jpa.hibernate.ddl-auto=create",
                "spring.jpa.open-in-view=false",
                "homeease.booking.otp.hmac-secret=journey-test-only-secret-not-a-credential"
        })
class LiveTrackingJourneyPostgresTest {

    @Configuration
    @ImportAutoConfiguration({
            HibernateJpaAutoConfiguration.class,
            JpaRepositoriesAutoConfiguration.class,
            TransactionAutoConfiguration.class,
            JacksonAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class})
    @EntityScan("com.homeease.backend.model.entity")
    @EnableJpaRepositories("com.homeease.backend.repository")
    @Import({
            BookingAssignmentService.class,
            BookingDomainEventService.class,
            BookingOtpService.class,
            ServiceSessionService.class,
            TrackingService.class,
            BookingRouteContextLoader.class,
            ArrivalDetectionService.class,
            LocationHistoryRecorder.class,
            LatestFixReader.class,
            DeviceStatusStore.class,
            TrackingBookingLoader.class,
            TrackingSnapshotService.class,
            TrackingFixConsumer.class})
    static class TestConfig {

        @Bean(destroyMethod = "close")
        EmbeddedPostgres embeddedPostgres() throws IOException {
            return EmbeddedPostgres.start();
        }

        @Bean
        DataSource dataSource(EmbeddedPostgres pg) {
            return DataSourceBuilder.create()
                    .url(pg.getJdbcUrl("postgres", "postgres") + "&stringtype=unspecified")
                    .username("postgres").password("").build();
        }
    }

    // Madhapur, Hyderabad (customer address). 0.001 deg latitude ~ 111 m.
    static final double DEST_LAT = 17.4486;
    static final double DEST_LNG = 78.3908;
    static final AtomicLong PHONE_SEQ = new AtomicLong(7_200_000_000L);

    @MockBean SimpMessagingTemplate messagingTemplate;
    @MockBean FcmNotificationService fcmNotificationService;
    @MockBean RedisTemplate<String, String> redisTemplate;
    @MockBean WorkerPresenceService presenceService;
    @MockBean WorkerService workerService;

    @Autowired TrackingService trackingService;
    @Autowired BookingAssignmentService assignmentService;
    @Autowired BookingOtpService otpService;
    @Autowired ServiceSessionService sessionService;
    @Autowired TrackingSnapshotService snapshotService;
    @Autowired LocationHistoryRecorder historyRecorder;
    @Autowired TrackingFixConsumer fixConsumer;
    @Autowired UserRepository userRepository;
    @Autowired WorkerRepository workerRepository;
    @Autowired ServiceRepository serviceRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired DataSource dataSource;
    @Autowired EntityManagerFactory entityManagerFactory;

    final Map<String, String> redis = new ConcurrentHashMap<>();
    JdbcTemplate jdbc;
    UUID bookingId;
    UUID customerId;
    UUID workerUserId;
    VerifiedCaller customer;
    VerifiedCaller partner;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DO $$ DECLARE r RECORD; BEGIN FOR r IN SELECT tablename FROM pg_tables WHERE schemaname = 'public' "
                + "LOOP EXECUTE 'TRUNCATE TABLE ' || quote_ident(r.tablename) || ' CASCADE'; END LOOP; END $$");
        redis.clear();

        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(i -> redis.get(i.<String>getArgument(0)));
        doAnswer(i -> { redis.put(i.getArgument(0), i.getArgument(1)); return null; })
                .when(ops).set(anyString(), anyString(), any(java.time.Duration.class));
        doAnswer(i -> { redis.put(i.getArgument(0), i.getArgument(1)); return null; })
                .when(ops).set(anyString(), anyString());
        when(redisTemplate.delete(anyString())).thenAnswer(i -> redis.remove(i.<String>getArgument(0)) != null);

        User c = userRepository.save(User.builder().fullName("Journey customer")
                .phoneNumber("+91" + PHONE_SEQ.incrementAndGet()).email("jc" + PHONE_SEQ.get() + "@it.invalid")
                .role(UserRole.CUSTOMER).build());
        User wu = userRepository.save(User.builder().fullName("Ravi (electrician)")
                .phoneNumber("+91" + PHONE_SEQ.incrementAndGet()).email("jw" + PHONE_SEQ.get() + "@it.invalid")
                .role(UserRole.WORKER).build());
        Worker w = workerRepository.save(Worker.builder().user(wu).kycStatus("APPROVED").isOnline(true)
                .isVerified(true).build());
        ServiceEntity svc = serviceRepository.save(ServiceEntity.builder().name("Electrician")
                .imageUrl("https://example.invalid/e.png").isActive(true).build());
        Booking b = bookingRepository.save(Booking.builder()
                .user(c).worker(w).service(svc).bookingType(BookingType.INSTANT).status(BookingStage.ACCEPTED)
                .pinCode("0000").scheduledAt(Instant.now()).userLat(DEST_LAT).userLng(DEST_LNG)
                .baseAmount(new BigDecimal("299.00")).totalAmount(new BigDecimal("299.00"))
                .paymentMethod(PaymentMode.COD).createdAt(Instant.now()).build());

        bookingId = b.getBookingId();
        customerId = c.getUserId();
        workerUserId = wu.getUserId();
        customer = new VerifiedCaller(customerId, UserRole.CUSTOMER);
        partner = new VerifiedCaller(workerUserId, UserRole.WORKER);
    }

    private LocationUpdateRequest fixNorth(double meters, long ts, double speed) {
        return LocationUpdateRequest.builder()
                .bookingId(bookingId.toString())
                .lat(DEST_LAT + meters / 111_195.0).lng(DEST_LNG)
                .bearing(180.0).speed(speed).accuracy(6.0).timestamp(ts)
                .build();
    }

    private String bookingStatus() {
        return jdbc.queryForObject("SELECT status FROM bookings WHERE booking_id = ?", String.class, bookingId);
    }

    private void inOpenSession(Runnable body) {
        EntityManager em = entityManagerFactory.createEntityManager();
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(em));
        try {
            body.run();
        } finally {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory);
            EntityManagerFactoryUtils.closeEntityManager(em);
        }
    }

    @Test
    @DisplayName("worker travels -> customer sees EN_ROUTE/NEARBY live -> geofence ARRIVED -> PIN -> SERVICE_STARTED")
    void fullJourney() {
        // ---- partner taps "Start trip"
        assignmentService.handleWorkerStartTravel(bookingId, workerUserId);
        assertThat(bookingStatus()).isEqualTo("ON_THE_WAY");

        Map<String, Object> beforeGps = snapshotService.snapshot(bookingId, customer);
        assertThat(beforeGps.get("phase")).isEqualTo("LOCATION_UNAVAILABLE");
        assertThat(beforeGps.get("live")).as("never LIVE without a fix").isEqualTo(false);
        assertThat(beforeGps).containsEntry("workerLocation", null);

        // ---- 900 m -> 100 m at 12.5 m/s, one fix every 2 s (timestamps in the recent past, strictly increasing)
        long ts = System.currentTimeMillis() - 100_000;
        boolean sawEnRoute = false;
        boolean sawNearby = false;
        long lastDistance = Long.MAX_VALUE;
        for (double d = 900; d >= 100; d -= 25) {
            LocationUpdateResult r = trackingService.updateWorkerLocation(bookingId, workerUserId, fixNorth(d, ts, 12.5));
            assertThat(r.isSuccess()).as("fix at %s m: %s", d, r.getMessage()).isTrue();
            Map<String, Object> snap = snapshotService.snapshot(bookingId, customer);
            assertThat(snap.get("live")).as("live with a fresh fix").isEqualTo(true);
            long dist = ((Number) snap.get("straightLineDistanceMeters")).longValue();
            assertThat(dist).as("distance shrinks as the partner approaches").isLessThan(lastDistance);
            lastDistance = dist;
            if ("PARTNER_EN_ROUTE".equals(snap.get("phase"))) {
                sawEnRoute = true;
                assertThat(dist).isGreaterThanOrEqualTo(500); // 500.04 m rounds to 500 but is outside the radius
            }
            if ("PARTNER_NEARBY".equals(snap.get("phase"))) {
                sawNearby = true;
                assertThat(dist).isLessThanOrEqualTo(500);
            }
            ts += 2_000;
        }
        assertThat(sawEnRoute).isTrue();
        assertThat(sawNearby).isTrue();
        assertThat(bookingStatus()).as("driving past the radius is not arrival").isEqualTo("ON_THE_WAY");
        verify(messagingTemplate, times(1)).convertAndSend(eq("/topic/booking/" + bookingId),
                argThat((Object m) -> m instanceof Map<?, ?> map && "PARTNER_NEARBY".equals(map.get("type"))));

        // ---- slows down and stops at the door: 70 m, 55 m, 45 m at walking speed, 3 s apart
        ts += 1_000;
        assertThat(trackingService.updateWorkerLocation(bookingId, workerUserId, fixNorth(70, ts, 0.5)).isSuccess()).isTrue();
        assertThat(bookingStatus()).as("one fix inside the radius is not an arrival").isEqualTo("ON_THE_WAY");
        ts += 3_000;
        trackingService.updateWorkerLocation(bookingId, workerUserId, fixNorth(55, ts, 0.5));
        assertThat(bookingStatus()).isEqualTo("ON_THE_WAY");
        ts += 3_000;
        trackingService.updateWorkerLocation(bookingId, workerUserId, fixNorth(45, ts, 0.0));

        // ---- automatic arrival
        assertThat(bookingStatus()).as("geofence confirmed after 3 consecutive slow fixes over 6 s").isEqualTo("ARRIVED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_otp WHERE booking_id = ? AND status = 'ACTIVE'",
                Long.class, bookingId)).isEqualTo(1L);
        verify(fcmNotificationService, atLeastOnce()).sendPushNotification(eq(customerId), anyString(), anyString());

        Map<String, Object> arrived = snapshotService.snapshot(bookingId, customer);
        assertThat(arrived.get("phase")).isEqualTo("ARRIVED");
        assertThat(arrived.get("live")).isEqualTo(false);
        assertThat(arrived.get("pinAvailable")).isEqualTo(true);
        assertThat(snapshotService.snapshot(bookingId, partner)).as("the partner never sees PIN fields")
                .doesNotContainKeys("pinAvailable", "pinLockedUntil");

        // a late GPS frame after arrival is refused
        LocationUpdateResult late = trackingService.updateWorkerLocation(bookingId, workerUserId, fixNorth(40, ts + 3_000, 0.0));
        assertThat(late.isSuccess()).isFalse();
        assertThat(late.getStatus()).isEqualTo("ARRIVED");

        // ---- customer reads the PIN, partner verifies it, service starts
        String pin = redis.get("booking:customer_pin:" + bookingId);
        assertThat(pin).matches("\\d{4}");
        inOpenSession(() -> {
            Booking booking = bookingRepository.findById(bookingId).orElseThrow();
            assertThat(otpService.verifyOtp(booking, workerUserId, pin)).isTrue();
            sessionService.startSession(booking, booking.getWorker());
        });
        assertThat(bookingStatus()).isEqualTo("IN_PROGRESS");
        assertThat(snapshotService.snapshot(bookingId, customer).get("phase")).isEqualTo("SERVICE_STARTED");

        // ---- durable history in PostgreSQL: sampled, ordered, inside the travelled corridor
        historyRecorder.flush();
        long rows = jdbc.queryForObject("SELECT count(*) FROM booking_location_history WHERE booking_id = ?", Long.class, bookingId);
        assertThat(rows).as("sampled, not one row per tick (36 accepted fixes)").isBetween(5L, 20L);
        Map<String, Object> history = snapshotService.history(bookingId, customer, 500);
        assertThat(history.get("count")).isEqualTo((int) rows);
        assertThat(history.get("source")).isEqualTo("postgresql");
    }

    @Test
    @DisplayName("cross-customer leakage and partner spoofing are refused")
    void authorization() {
        assignmentService.handleWorkerStartTravel(bookingId, workerUserId);
        trackingService.updateWorkerLocation(bookingId, workerUserId, fixNorth(800, System.currentTimeMillis() - 1_000, 10.0));

        VerifiedCaller otherCustomer = new VerifiedCaller(UUID.randomUUID(), UserRole.CUSTOMER);
        assertThatThrownBy(() -> snapshotService.snapshot(bookingId, otherCustomer))
                .isInstanceOf(TrackingApiException.class)
                .extracting(e -> ((TrackingApiException) e).getCode())
                .isEqualTo(TrackingApiException.Code.NOT_BOOKING_PARTICIPANT);
        assertThatThrownBy(() -> snapshotService.history(bookingId, otherCustomer, 10))
                .isInstanceOf(TrackingApiException.class);

        // the customer cannot act as the partner (e.g. post GPS or device status)
        assertThatThrownBy(() -> snapshotService.requireAssignedWorker(bookingId, customer))
                .isInstanceOf(TrackingApiException.class)
                .extracting(e -> ((TrackingApiException) e).getCode())
                .isEqualTo(TrackingApiException.Code.ROLE_NOT_ALLOWED);

        // another partner cannot inject positions for this booking
        UUID intruder = UUID.randomUUID();
        assertThatThrownBy(() -> trackingService.updateWorkerLocation(bookingId, intruder,
                fixNorth(700, System.currentTimeMillis(), 10.0)))
                .isInstanceOf(SecurityException.class);
        // and a missing identity no longer slips through
        assertThatThrownBy(() -> trackingService.updateWorkerLocation(bookingId, null,
                fixNorth(700, System.currentTimeMillis(), 10.0)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("fixes relayed from the tracking service (Redis pub/sub) feed history and arrival the same way")
    void relayedFixesFeedHistoryAndArrival() {
        assignmentService.handleWorkerStartTravel(bookingId, workerUserId);
        long ts = System.currentTimeMillis() - 30_000;
        double[] meters = {60, 50, 40};
        for (double m : meters) {
            String body = String.format(java.util.Locale.US,
                    "{\"destination\":\"/topic/tracking/%s\",\"bookingId\":\"%s\",\"payload\":{\"bookingId\":\"%s\","
                            + "\"workerId\":\"%s\",\"lat\":%.7f,\"lng\":%.7f,\"accuracy\":5.0,\"speed\":0.3,"
                            + "\"bearing\":180.0,\"timestamp\":%d,\"serverTimestamp\":%d}}",
                    bookingId, bookingId, bookingId, workerUserId, DEST_LAT + m / 111_195.0, DEST_LNG, ts, ts);
            assertThat(fixConsumer.handle(body)).isTrue();
            ts += 3_000;
        }
        assertThat(bookingStatus()).isEqualTo("ARRIVED");

        // envelope/payload booking mismatch is never attributed to the booking
        String forged = String.format("{\"bookingId\":\"%s\",\"payload\":{\"bookingId\":\"%s\",\"lat\":17.0,\"lng\":78.0,\"timestamp\":%d}}",
                bookingId, UUID.randomUUID(), System.currentTimeMillis());
        assertThat(fixConsumer.handle(forged)).isFalse();

        historyRecorder.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_location_history WHERE booking_id = ?",
                Long.class, bookingId)).isGreaterThanOrEqualTo(1L);
    }
}
