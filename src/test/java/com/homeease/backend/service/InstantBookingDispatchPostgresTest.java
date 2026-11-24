package com.homeease.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.homeease.backend.dto.BookingDto.BookingResponse;
import com.homeease.backend.dto.BookingDto.InstantBookingRequest;
import com.homeease.backend.dto.BookingDto.SubServiceRequestItem;
import com.homeease.backend.dto.CartDto.CheckoutFromCartRequest;
import com.homeease.backend.dto.WorkerDto.WorkerBookingRequestResponse;
import com.homeease.backend.exception.ServiceabilityException;
import com.homeease.backend.model.entity.Booking;
import com.homeease.backend.model.entity.BookingCart;
import com.homeease.backend.model.entity.BookingCartItem;
import com.homeease.backend.model.entity.ServiceEntity;
import com.homeease.backend.model.entity.ServiceablePincode;
import com.homeease.backend.model.entity.SubService;
import com.homeease.backend.model.entity.User;
import com.homeease.backend.model.entity.Worker;
import com.homeease.backend.model.entity.WorkerServiceId;
import com.homeease.backend.model.entity.WorkerServiceLink;
import com.homeease.backend.model.enums.BookingActorType;
import com.homeease.backend.model.enums.BookingEventType;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.BookingType;
import com.homeease.backend.model.enums.PaymentMode;
import com.homeease.backend.model.enums.UserRole;
import com.homeease.backend.repository.BookingCartItemRepository;
import com.homeease.backend.repository.BookingCartRepository;
import com.homeease.backend.repository.BookingRepository;
import com.homeease.backend.repository.ServiceRepository;
import com.homeease.backend.repository.ServiceablePincodeRepository;
import com.homeease.backend.repository.SubServiceRepository;
import com.homeease.backend.repository.UserRepository;
import com.homeease.backend.repository.WorkerRepository;
import com.homeease.backend.repository.WorkerServiceLinkRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Instant booking -> worker dispatch against a REAL PostgreSQL (zonky embedded-postgres: real binaries, throw-away temp
 * directory, random local port; nothing external is touched, no credentials involved).
 *
 * <p>Unlike the Mockito suites, this drives the REAL services, repositories, entities, transaction manager and
 * Hibernate. The schema is generated from the JPA entities, so every {@code @ManyToOne} becomes a real foreign key
 * ({@code booking_assignment.booking_id -> bookings}, {@code dispatch_audit_logs.booking_id -> bookings}, ...), which
 * is exactly what the production Flyway schema enforces too. PostGIS is not needed: {@code Worker.location} and
 * {@code Booking.userLocation} are {@code @Transient}. The Flyway chain itself is covered by the PostGIS CI job.
 *
 * <p>Only the genuinely external collaborators are replaced: STOMP broker, FCM, Redis and the heartbeat store. An
 * independent JDBC connection (autocommit, READ COMMITTED) observes what is really COMMITTED at the instant each
 * notification is sent, which is what "notify only after commit" means.
 *
 * <p>Fixtures exist only inside this disposable database and use real Hyderabad localities (Madhapur 500081, Hitec
 * City, Kondapur, Banjara Hills, Secunderabad) so pincode and distance rules run with realistic coordinates.
 */
@SpringBootTest(
        classes = InstantBookingDispatchPostgresTest.TestConfig.class,
        properties = {
                "spring.jpa.hibernate.ddl-auto=create",
                "spring.jpa.open-in-view=false",
                // 2 offers per batch, so the "next batch" cascade (rejection / timeout) is reachable with few workers
                "homeease.dispatch.batch-size=2"
        })
class InstantBookingDispatchPostgresTest {

    // ---- real localities (public knowledge; no personal data) -------------------------------------------------
    static final String MADHAPUR_PINCODE = "500081";
    static final double MADHAPUR_LAT = 17.4486, MADHAPUR_LNG = 78.3908;         // the customer
    static final double HITEC_CITY_LAT = 17.4435, HITEC_CITY_LNG = 78.3772;      // ~1.5 km from the customer
    static final double KONDAPUR_LAT = 17.4600, KONDAPUR_LNG = 78.3650;          // ~3.0 km
    static final double GACHIBOWLI_LAT = 17.4401, GACHIBOWLI_LNG = 78.3489;      // ~4.5 km
    static final double BANJARA_HILLS_LAT = 17.4126, BANJARA_HILLS_LNG = 78.4482; // ~7.3 km: only the 10 km tier reaches it
    static final double SECUNDERABAD_LAT = 17.4399, SECUNDERABAD_LNG = 78.4983;   // ~11 km: only the 15 km tier reaches it

    /** One worker alert = STOMP on 4 topics (workerId and userId, each /requests and /stream) + 1 FCM push. */
    static final int PUSHES_PER_WORKER_ALERT = 5;
    /** One customer notification = STOMP on the booking topic and on the customer topic + 1 FCM push. */
    static final int PUSHES_PER_CUSTOMER_NOTIFICATION = 3;

    @Configuration
    @ImportAutoConfiguration({
            HibernateJpaAutoConfiguration.class,
            JpaRepositoriesAutoConfiguration.class,
            TransactionAutoConfiguration.class,
            JacksonAutoConfiguration.class})
    @EntityScan("com.homeease.backend.model.entity")
    @EnableJpaRepositories("com.homeease.backend.repository")
    @Import({
            BookingAssignmentService.class,
            InstantBookingService.class,
            CartCheckoutService.class,
            PricingEngineService.class,
            BookingDomainEventService.class,
            ServiceAreaService.class,
            UserAddressService.class,
            BookingOtpService.class,
            ServiceSessionService.class,
            BookingDispatchConsumer.class,
            BookingCancellationService.class,
            BookingNotificationService.class,
            ReassignmentService.class,
            InstantDispatchRecoveryJob.class})
    static class TestConfig {

        /** Real PostgreSQL; closed by Spring AFTER the connection pool and EntityManagerFactory are destroyed. */
        @Bean(destroyMethod = "close")
        EmbeddedPostgres embeddedPostgres() throws IOException {
            return EmbeddedPostgres.start();
        }

        @Bean
        DataSource dataSource(EmbeddedPostgres pg) {
            return DataSourceBuilder.create()
                    .url(pg.getJdbcUrl("postgres", "postgres") + "&stringtype=unspecified")
                    .username("postgres")
                    .password("")
                    .build();
        }
    }

    // ---- genuinely external collaborators --------------------------------------------------------------------
    @MockBean SimpMessagingTemplate messagingTemplate;
    @MockBean FcmNotificationService fcmNotificationService;
    @MockBean RedisTemplate<String, String> redisTemplate;
    @MockBean WorkerPresenceService presenceService;
    // Dispatch persistence is exercised with real JPA; the external tracking session
    // collaborator is mocked and must explicitly acknowledge activation.
    @MockBean TrackingService trackingService;

    // ---- real beans under test -------------------------------------------------------------------------------
    @Autowired InstantBookingService instantBookingService;
    @Autowired CartCheckoutService cartCheckoutService;
    @Autowired BookingAssignmentService assignmentService;
    @Autowired BookingDispatchConsumer dispatchConsumer;
    @Autowired BookingCancellationService cancellationService;
    @Autowired BookingOtpService bookingOtpService;
    @Autowired ServiceSessionService sessionService;
    @Autowired InstantDispatchRecoveryJob recoveryJob;
    @Autowired ReassignmentService reassignmentService;
    @Autowired BookingDomainEventService domainEventService;

    @Autowired UserRepository userRepository;
    @Autowired ServiceRepository serviceRepository;
    @Autowired SubServiceRepository subServiceRepository;
    @Autowired WorkerRepository workerRepository;
    @Autowired WorkerServiceLinkRepository workerServiceLinkRepository;
    @Autowired ServiceablePincodeRepository pincodeRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired BookingCartRepository cartRepository;
    @Autowired BookingCartItemRepository cartItemRepository;

    @Autowired DataSource dataSource;
    @Autowired org.springframework.core.env.Environment environment;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired TransactionTemplate transactionTemplate;

    /** Independent connection: sees only COMMITTED data, never the transaction that is currently running. */
    JdbcTemplate jdbc;

    ServiceEntity service;
    SubService subService;

    final GeoOperations<String, String> geoOperations = mockGeo();
    final ValueOperations<String, String> valueOperations = mockValueOps();

    /** A notification that was sent, with what a SECOND connection could see as committed at that very moment. */
    record Push(String channel, String destination, int committedAssignments, String committedBookingStatuses) { }

    final List<Push> pushes = new CopyOnWriteArrayList<>();
    static final AtomicLong PHONE_SEQ = new AtomicLong(7_000_000_000L);

    @SuppressWarnings("unchecked")
    private static GeoOperations<String, String> mockGeo() {
        return mock(GeoOperations.class);
    }

    @SuppressWarnings("unchecked")
    private static ValueOperations<String, String> mockValueOps() {
        return mock(ValueOperations.class);
    }

    @BeforeEach
    void freshDatabaseAndFixtures() {
        jdbc = new JdbcTemplate(dataSource);
        resetDatabase();
        seedCatalog();
        installNotificationRecorders();
    }

    void resetDatabase() {
        jdbc.execute("DROP TRIGGER IF EXISTS it_fail_assignment_trg ON booking_assignment");
        jdbc.execute("DROP TRIGGER IF EXISTS it_slow_assignment_update_trg ON booking_assignment");
        jdbc.execute("DO $$ DECLARE r RECORD; BEGIN "
                + "FOR r IN SELECT tablename FROM pg_tables WHERE schemaname = 'public' LOOP "
                + "EXECUTE 'TRUNCATE TABLE ' || quote_ident(r.tablename) || ' CASCADE'; END LOOP; END $$");
        pushes.clear();
    }

    void seedCatalog() {
        service = serviceRepository.save(ServiceEntity.builder()
                .name("Electrician (integration fixture)")
                .imageUrl("https://example.invalid/electrician.png")
                .isActive(true)
                .build());
        subService = subServiceRepository.save(SubService.builder()
                .service(service)
                .name("Ceiling fan repair")
                .basePrice(new BigDecimal("199.00"))
                .estimatedMins(30)
                .imageUrl("https://example.invalid/fan.png")
                .isActive(true)
                .build());
        pincodeRepository.save(ServiceablePincode.builder()
                .pincode(MADHAPUR_PINCODE)
                .areaName("Madhapur")
                .isActive(true)
                .build());
    }

    void installNotificationRecorders() {
        // every STOMP push and FCM push is recorded together with what a second connection sees as COMMITTED right now
        doAnswer(inv -> {
            pushes.add(new Push("STOMP", inv.getArgument(0), committedAssignments(), committedBookingStatuses()));
            return null;
        }).when(messagingTemplate).convertAndSend(anyString(), any(Object.class));
        doAnswer(inv -> {
            pushes.add(new Push("FCM", "user_" + inv.getArgument(0), committedAssignments(), committedBookingStatuses()));
            return null;
        }).when(fcmNotificationService).sendPushNotification(any(UUID.class), anyString(), anyString());

        // Redis is reachable, the worker GEO index is simply empty, and every worker has a live heartbeat
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(geoOperations.radius(anyString(), any(Circle.class))).thenReturn(noGeoHits());
        when(presenceService.isDispatchable(any(Worker.class))).thenReturn(true);
    }

    // ================================================================================================================
    // 1. THE DEFECT: booking creation must leave persisted assignments, and workers must be alerted only after commit
    // ================================================================================================================

    @Test
    @DisplayName("Instant booking: assignments are persisted, workers are alerted only after commit, and GET /workers/me/requests returns the request")
    void instantBooking_persistsAssignments_alertsOnlyAfterCommit_andWorkersCanFetchTheRequest() {
        Worker nearWorker = newOnlineWorker("Hitec City electrician", HITEC_CITY_LAT, HITEC_CITY_LNG, MADHAPUR_PINCODE);
        Worker secondWorker = newOnlineWorker("Kondapur electrician", KONDAPUR_LAT, KONDAPUR_LNG, MADHAPUR_PINCODE);
        User customer = newCustomer("Madhapur customer");

        BookingResponse response = book(customer);
        UUID bookingId = response.getBookingId();

        // --- the booking itself -------------------------------------------------------------------------------
        assertThat(count("SELECT count(*) FROM bookings")).as("exactly one booking row (no phantom copy)").isEqualTo(1);
        assertThat(bookingStatus(bookingId)).as("committed booking status").isEqualTo("SEARCHING");
        assertThat(response.getStatus()).as("API contract: the create response reports SEARCHING").isEqualTo(BookingStage.SEARCHING);

        // --- assignment persistence + foreign-key correctness -------------------------------------------------
        assertThat(count("SELECT count(*) FROM booking_assignment WHERE booking_id = ?", bookingId))
                .as("one PENDING assignment per eligible worker").isEqualTo(2);
        assertThat(count("SELECT count(*) FROM booking_assignment a JOIN bookings b ON b.booking_id = a.booking_id "
                + "JOIN workers w ON w.worker_id = a.worker_id WHERE a.status = 'PENDING' AND a.expires_at > now()"))
                .as("every assignment joins to a real booking and a real worker").isEqualTo(2);
        assertThat(count("SELECT count(*) FROM dispatch_audit_logs WHERE booking_id = ?", bookingId))
                .as("dispatch audit row persisted").isEqualTo(1);

        // --- the Kafka outbox row is still written atomically with the booking (architecture unchanged) --------
        assertThat(count("SELECT count(*) FROM booking_event_outbox WHERE aggregate_id = ? AND event_type = 'BOOKING_CREATED' "
                + "AND status = 'PENDING'", bookingId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM booking_event_history WHERE booking_id = ?", bookingId)).isEqualTo(1);

        // --- notifications: both workers alerted over STOMP and FCM, never before their assignment was committed -
        // Per worker the existing dual-channel design sends STOMP on the workerId topic AND on the userId topic
        // (clients subscribe to either), each as /requests and /stream, plus one FCM push.
        assertThat(pushes).as("2 workers x (4 STOMP topics + 1 FCM)").hasSize(2 * PUSHES_PER_WORKER_ALERT);
        assertThat(pushes).as("no alert may be sent while the assignment is still uncommitted")
                .allSatisfy(a -> assertThat(a.committedAssignments())
                        .as(a.channel() + " alert " + a.destination() + " saw committed assignments").isGreaterThanOrEqualTo(1));
        assertThat(pushes).extracting(Push::destination).containsExactlyInAnyOrder(
                "/topic/worker/" + nearWorker.getWorkerId() + "/requests",
                "/topic/worker/" + nearWorker.getWorkerId() + "/stream",
                "/topic/worker/" + nearWorker.getUser().getUserId() + "/requests",
                "/topic/worker/" + nearWorker.getUser().getUserId() + "/stream",
                "user_" + nearWorker.getUser().getUserId(),
                "/topic/worker/" + secondWorker.getWorkerId() + "/requests",
                "/topic/worker/" + secondWorker.getWorkerId() + "/stream",
                "/topic/worker/" + secondWorker.getUser().getUserId() + "/requests",
                "/topic/worker/" + secondWorker.getUser().getUserId() + "/stream",
                "user_" + secondWorker.getUser().getUserId());

        // --- delivery bookkeeping ------------------------------------------------------------------------------
        // These rows are written by the POST-COMMIT transaction, and that code deliberately swallows errors (a failed
        // bookkeeping insert must never fail a booking that is already committed). Silence is therefore not proof, so
        // read them back over the independent connection. Written inside afterCommit WITHOUT a fresh transaction they
        // would join the already-committed one and silently never be committed.
        assertThat(count("SELECT count(*) FROM notification_delivery WHERE booking_id = ?", bookingId))
                .as("one WS row and one FCM row per alerted worker, all committed").isEqualTo(4);
        assertThat(count("SELECT count(*) FROM notification_delivery WHERE booking_id = ? AND channel = 'WS' AND status = 'SENT' "
                        + "AND topic_destination IN (?, ?)", bookingId,
                "/topic/worker/" + nearWorker.getWorkerId() + "/requests",
                "/topic/worker/" + secondWorker.getWorkerId() + "/requests"))
                .as("a WS delivery row for each worker's /requests topic").isEqualTo(2);
        assertThat(count("SELECT count(*) FROM notification_delivery WHERE booking_id = ? AND channel = 'FCM' AND status = 'SENT'",
                bookingId)).as("an FCM delivery row for each worker").isEqualTo(2);
        assertThat(count("SELECT count(*) FROM notification_delivery d JOIN workers w ON w.worker_id = d.worker_id "
                + "JOIN bookings b ON b.booking_id = d.booking_id WHERE d.booking_id = ?", bookingId))
                .as("every delivery row joins to a real worker and booking").isEqualTo(4);

        // --- GET /workers/me/requests --------------------------------------------------------------------------
        List<WorkerBookingRequestResponse> nearRequests = assignmentService.getPendingRequestsForWorker(nearWorker.getUser().getUserId());
        assertThat(nearRequests).hasSize(1);
        assertThat(nearRequests.get(0).getBookingId()).isEqualTo(bookingId);
        assertThat(nearRequests.get(0).getServicePincode()).isEqualTo(MADHAPUR_PINCODE);
        assertThat(assignmentService.getPendingRequestsForWorker(secondWorker.getUser().getUserId())).hasSize(1);
    }

    @Test
    @DisplayName("Same request as production: inside an open EntityManager (open-in-view), the persistence context is shared across the transactions")
    void instantBooking_worksWithAnOpenEntityManagerInView_likeARealHttpRequest() {
        Worker worker = newOnlineWorker("Hitec City electrician", HITEC_CITY_LAT, HITEC_CITY_LNG, MADHAPUR_PINCODE);
        User customer = newCustomer("Madhapur customer");

        AtomicReference<BookingResponse> response = new AtomicReference<>();
        inRequest(() -> response.set(book(customer)));

        UUID bookingId = response.get().getBookingId();
        assertThat(response.get().getStatus()).isEqualTo(BookingStage.SEARCHING);
        assertThat(count("SELECT count(*) FROM bookings")).isEqualTo(1);
        assertThat(bookingStatus(bookingId)).isEqualTo("SEARCHING");
        assertThat(count("SELECT count(*) FROM booking_assignment WHERE booking_id = ? AND worker_id = ?", bookingId, worker.getWorkerId()))
                .isEqualTo(1);
        assertThat(pushes).isNotEmpty().allSatisfy(p -> assertThat(p.committedAssignments()).isGreaterThanOrEqualTo(1));
        assertThat(count("SELECT count(*) FROM notification_delivery WHERE booking_id = ? AND worker_id = ? AND status = 'SENT'",
                bookingId, worker.getWorkerId()))
                .as("delivery bookkeeping (one WS + one FCM row) is committed although the request's EntityManager stays open")
                .isEqualTo(2);
        assertThat(assignmentService.getPendingRequestsForWorker(worker.getUser().getUserId())).hasSize(1);
    }

    @Test
    @DisplayName("If the caller is itself inside an uncommitted transaction, dispatch waits for that commit instead of running blind")
    void instantBooking_calledInsideAnOuterTransaction_dispatchesOnlyAfterTheOuterCommit() {
        Worker worker = newOnlineWorker("Hitec City electrician", HITEC_CITY_LAT, HITEC_CITY_LNG, MADHAPUR_PINCODE);
        User customer = newCustomer("Madhapur customer");
        AtomicReference<BookingResponse> inside = new AtomicReference<>();
        AtomicReference<Integer> assignmentsBeforeOuterCommit = new AtomicReference<>();

        transactionTemplate.executeWithoutResult(status -> {
            inside.set(book(customer));
            assignmentsBeforeOuterCommit.set(committedAssignments());
            assertThat(pushes).as("nothing may be announced before the outer transaction commits").isEmpty();
        });

        assertThat(inside.get().getStatus()).as("snapshot taken before dispatch").isEqualTo(BookingStage.REQUESTED);
        assertThat(assignmentsBeforeOuterCommit.get()).isZero();
        UUID bookingId = inside.get().getBookingId();
        assertThat(bookingStatus(bookingId)).isEqualTo("SEARCHING");
        assertThat(count("SELECT count(*) FROM booking_assignment WHERE booking_id = ? AND worker_id = ?", bookingId, worker.getWorkerId()))
                .isEqualTo(1);
        assertThat(pushes).isNotEmpty().allSatisfy(p -> assertThat(p.committedAssignments()).isGreaterThanOrEqualTo(1));
        assertThat(count("SELECT count(*) FROM notification_delivery WHERE booking_id = ? AND worker_id = ? AND status = 'SENT'",
                bookingId, worker.getWorkerId()))
                .as("delivery bookkeeping (one WS + one FCM row) survives the nested post-commit transactions")
                .isEqualTo(2);
    }

    // ================================================================================================================
    // 2. Worker acceptance
    // ================================================================================================================

    @Test
    @DisplayName("Worker acceptance: booking ACCEPTED, winner's offer ACCEPTED, other offers DROPPED, customer told only after commit")
    void workerAcceptance_isAtomic_andTheCustomerIsNotifiedOnlyAfterCommit() {
        Worker winner = newOnlineWorker("Hitec City electrician", HITEC_CITY_LAT, HITEC_CITY_LNG, MADHAPUR_PINCODE);
        Worker other = newOnlineWorker("Kondapur electrician", KONDAPUR_LAT, KONDAPUR_LNG, MADHAPUR_PINCODE);
        User customer = newCustomer("Madhapur customer");
        UUID bookingId = book(customer).getBookingId();
        pushes.clear();

        assignmentService.handleWorkerAcceptance(bookingId, winner.getUser().getUserId());

        assertThat(bookingStatus(bookingId)).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT worker_id FROM bookings WHERE booking_id = ?", UUID.class, bookingId))
                .isEqualTo(winner.getWorkerId());
        assertThat(assignmentStatus(bookingId, winner)).isEqualTo("ACCEPTED");
        assertThat(assignmentStatus(bookingId, other)).isEqualTo("DROPPED");
        assertThat(count("SELECT count(*) FROM booking_event_outbox WHERE aggregate_id = ? AND event_type = 'PARTNER_ACCEPTED'", bookingId))
                .isEqualTo(1);

        assertThat(pushes).as("STOMP booking topic + STOMP customer topic + FCM").hasSize(PUSHES_PER_CUSTOMER_NOTIFICATION);
        assertThat(pushes).as("the customer must only hear about it once it is committed")
                .allSatisfy(p -> assertThat(p.committedBookingStatuses()).isEqualTo("ACCEPTED"));
        // the customer's delivery bookkeeping is written by the post-commit transaction too (and its errors are swallowed)
        assertThat(count("SELECT count(*) FROM notification_delivery WHERE booking_id = ? AND user_id = ? AND worker_id = ? "
                + "AND status = 'SENT'", bookingId, customer.getUserId(), winner.getWorkerId()))
                .as("one WS row and one FCM row for the customer notification, committed").isEqualTo(2);

        assertThat(assignmentService.getPendingRequestsForWorker(other.getUser().getUserId())).isEmpty();
        assertThatThrownBy(() -> assignmentService.handleWorkerAcceptance(bookingId, other.getUser().getUserId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer searching");
    }

    @Test
    @DisplayName("Concurrent acceptance: of two workers racing for the same booking exactly one wins, every time")
    void concurrentAcceptance_exactlyOneWorkerWins() throws Exception {
        for (int round = 1; round <= 5; round++) {
            resetDatabase();
            seedCatalog();
            Worker a = newOnlineWorker("Hitec City electrician " + round, HITEC_CITY_LAT, HITEC_CITY_LNG, MADHAPUR_PINCODE);
            Worker b = newOnlineWorker("Kondapur electrician " + round, KONDAPUR_LAT, KONDAPUR_LNG, MADHAPUR_PINCODE);
            UUID bookingId = book(newCustomer("Madhapur customer " + round)).getBookingId();

            List<Boolean> won = runConcurrently(
                    () -> accept(bookingId, a),
                    () -> accept(bookingId, b));

            assertThat(won).as("round " + round + ": exactly one acceptance may succeed").containsOnlyOnce(true);
            Worker winner = won.get(0) ? a : b;
            Worker loser = won.get(0) ? b : a;
            assertThat(bookingStatus(bookingId)).isEqualTo("ACCEPTED");
            assertThat(jdbc.queryForObject("SELECT worker_id FROM bookings WHERE booking_id = ?", UUID.class, bookingId))
                    .isEqualTo(winner.getWorkerId());
            assertThat(assignmentStatus(bookingId, winner)).isEqualTo("ACCEPTED");
            assertThat(assignmentStatus(bookingId, loser)).isEqualTo("DROPPED");
            assertThat(count("SELECT count(*) FROM booking_event_outbox WHERE aggregate_id = ? AND event_type = 'PARTNER_ACCEPTED'", bookingId))
                    .as("only the winner records the acceptance event").isEqualTo(1);
        }
    }

    // ================================================================================================================
    // 3. Transaction rollback + recovery
    // ================================================================================================================

    @Test
    @DisplayName("A failure inside dispatch rolls ALL of it back (no partial offers, no status change, nobody notified); the recovery job then dispatches it")
    void dispatchFailure_rollsBackCompletely_andTheRecoveryJobDispatchesTheBookingLater() {
        Worker first = newOnlineWorker("Hitec City electrician", HITEC_CITY_LAT, HITEC_CITY_LNG, MADHAPUR_PINCODE);
        Worker second = newOnlineWorker("Kondapur electrician", KONDAPUR_LAT, KONDAPUR_LNG, MADHAPUR_PINCODE);
        User customer = newCustomer("Madhapur customer");

        // a REAL database failure in the middle of dispatch: inserting the SECOND worker's offer raises an error
        failAssignmentInsertsFor(second);
        BookingResponse recent;
        BookingResponse stale;
        try {
            recent = book(customer);
            stale = book(newCustomer("Another Madhapur customer"));
        } finally {
            removeAssignmentFailure();
        }

        // the booking transaction is unaffected: both bookings exist, are REQUESTED, and their events were written
        assertThat(recent.getStatus()).as("response tells the truth: dispatch has not happened").isEqualTo(BookingStage.REQUESTED);
        assertThat(count("SELECT count(*) FROM bookings WHERE status = 'REQUESTED'")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM booking_event_outbox WHERE event_type = 'BOOKING_CREATED'")).isEqualTo(2);
        // ... while the failed dispatch left NOTHING behind, not even the first worker's offer
        assertThat(count("SELECT count(*) FROM booking_assignment")).as("no partial offers").isZero();
        assertThat(count("SELECT count(*) FROM dispatch_audit_logs")).as("no audit row from a rolled-back dispatch").isZero();
        assertThat(pushes).as("nobody was told about offers that never committed").isEmpty();

        // too young: a dispatch that is still running normally must never be raced
        assertThat(recoveryJob.recoverUndispatchedInstantBookings()).isZero();

        // age one booking to 2 minutes (recoverable) and the other to 30 minutes (outside the recovery window)
        jdbc.update("UPDATE bookings SET created_at = now() - interval '2 minutes' WHERE booking_id = ?", recent.getBookingId());
        jdbc.update("UPDATE bookings SET created_at = now() - interval '30 minutes' WHERE booking_id = ?", stale.getBookingId());

        assertThat(recoveryJob.recoverUndispatchedInstantBookings()).isEqualTo(1);

        assertThat(bookingStatus(recent.getBookingId())).isEqualTo("SEARCHING");
        assertThat(count("SELECT count(*) FROM booking_assignment WHERE booking_id = ?", recent.getBookingId())).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM booking_assignment WHERE booking_id = ? AND worker_id IN (?, ?)",
                recent.getBookingId(), first.getWorkerId(), second.getWorkerId())).isEqualTo(2);
        assertThat(pushes).hasSize(2 * PUSHES_PER_WORKER_ALERT)
                .allSatisfy(p -> assertThat(p.committedAssignments()).isGreaterThanOrEqualTo(1));
        assertThat(bookingStatus(stale.getBookingId())).as("outside the window: left alone").isEqualTo("REQUESTED");

        // idempotent: nothing left to recover, nothing offered twice
        assertThat(recoveryJob.recoverUndispatchedInstantBookings()).isZero();
        assertThat(count("SELECT count(*) FROM booking_assignment WHERE booking_id = ?", recent.getBookingId())).isEqualTo(2);
        assertThat(pushes).hasSize(2 * PUSHES_PER_WORKER_ALERT);
    }

    @Test
    @DisplayName("Not serviceable: the booking transaction rolls back completely and nobody is notified")
    void notServiceablePincode_rollsBack_noBooking_noEvent_noNotification() {
        newOnlineWorker("Hitec City electrician", HITEC_CITY_LAT, HITEC_CITY_LNG, MADHAPUR_PINCODE);
        User customer = newCustomer("Customer outside the service area");
        InstantBookingRequest request = instantRequest();
        request.setPincode("560001"); // Bengaluru: not serviceable in this database

        assertThatThrownBy(() -> instantBookingService.createInstantBooking(customer.getUserId(), request, null))
                .isInstanceOf(ServiceabilityException.class);

        assertThat(count("SELECT count(*) FROM bookings")).isZero();
        assertThat(count("SELECT count(*) FROM booking_event_outbox")).isZero();
        assertThat(count("SELECT count(*) FROM booking_event_history")).isZero();
        assertThat(count("SELECT count(*) FROM booking_assignment")).isZero();
        assertThat(pushes).isEmpty();
    }

    // ================================================================================================================
    // 4. Duplicate events
    // ================================================================================================================

    @Test
    @DisplayName("Duplicate request (same idempotency key): one booking, one event, one set of offers, one set of alerts")
    void duplicateRequest_withTheSameIdempotencyKey_isServedOnce() {
        newOnlineWorker("Hitec City electrician", HITEC_CITY_LAT, HITEC_CITY_LNG, MADHAPUR_PINCODE);
        newOnlineWorker("Kondapur electrician", KONDAPUR_LAT, KONDAPUR_LNG, MADHAPUR_PINCODE);
        User customer = newCustomer("Madhapur customer");
        String key = UUID.randomUUID().toString();

        BookingResponse first = instantBookingService.createInstantBooking(customer.getUserId(), instantRequest(), key);
        int pushesAfterFirst = pushes.size();
        BookingResponse second = instantBookingService.createInstantBooking(customer.getUserId(), instantRequest(), key);

        assertThat(second.getBookingId()).isEqualTo(first.getBookingId());
        assertThat(count("SELECT count(*) FROM bookings")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM booking_event_outbox WHERE event_type = 'BOOKING_CREATED'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM booking_assignment")).isEqualTo(2);
        assertThat(pushes).as("the replay must not alert anybody again").hasSize(pushesAfterFirst);
    }

    @Test
    @DisplayName("Duplicate Kafka delivery of BOOKING_CREATED for an already-dispatched booking changes nothing")
    void redeliveredBookingCreatedEvent_forADispatchedBooking_offersNothingAgain() {
        newOnlineWorker("Hitec City electrician", HITEC_CITY_LAT, HITEC_CITY_LNG, MADHAPUR_PINCODE);
        newOnlineWorker("Kondapur electrician", KONDAPUR_LAT, KONDAPUR_LNG, MADHAPUR_PINCODE);
        UUID bookingId = book(newCustomer("Madhapur customer")).getBookingId();
        String payload = outboxPayload(bookingId);
        pushes.clear();

        dispatchConsumer.consumeBookingEvent(record(bookingId, payload));
        dispatchConsumer.consumeBookingEvent(record(bookingId, payload));

        assertThat(count("SELECT count(*) FROM booking_assignment WHERE booking_id = ?", bookingId)).isEqualTo(2);
        assertThat(bookingStatus(bookingId)).isEqualTo("SEARCHING");
        assertThat(pushes).as("no second alert for the same job").isEmpty();
    }

    @Test
    @DisplayName("Two consumers handling the same BOOKING_CREATED event at once dispatch it exactly once")
    void concurrentDeliveryOfTheSameEvent_dispatchesExactlyOnce() throws Exception {
        newOnlineWorker("Hitec City electrician", HITEC_CITY_LAT, HITEC_CITY_LNG, MADHAPUR_PINCODE);
        Worker blocked = newOnlineWorker("Kondapur electrician", KONDAPUR_LAT, KONDAPUR_LNG, MADHAPUR_PINCODE);
        User customer = newCustomer("Madhapur customer");

        // a booking that was saved but not yet dispatched (the exact state the Kafka consumer exists to handle)
        failAssignmentInsertsFor(blocked);
        UUID bookingId;
        try {
            bookingId = book(customer).getBookingId();
        } finally {
            removeAssignmentFailure();
        }
        assertThat(bookingStatus(bookingId)).isEqualTo("REQUESTED");
        String payload = outboxPayload(bookingId);
        pushes.clear();

        runConcurrently(
                () -> { dispatchConsumer.consumeBookingEvent(record(bookingId, payload)); return true; },
                () -> { dispatchConsumer.consumeBookingEvent(record(bookingId, payload)); return true; });

        assertThat(count("SELECT count(*) FROM booking_assignment WHERE booking_id = ?", bookingId))
                .as("each worker offered the job exactly once").isEqualTo(2);
        assertThat(bookingStatus(bookingId)).isEqualTo("SEARCHING");
        assertThat(pushes).as("one set of alerts: 2 workers x (4 STOMP + FCM)").hasSize(2 * PUSHES_PER_WORKER_ALERT);
    }

    @Test
    @DisplayName("BOOKING_CREATED for an unassigned SCHEDULED booking must not be dispatched like an instant job")
    void scheduledBooking_isNeverInstantDispatchedByTheConsumer() {
        newOnlineWorker("Hitec City electrician", HITEC_CITY_LAT, HITEC_CITY_LNG, MADHAPUR_PINCODE);
        User customer = newCustomer("Madhapur customer");
        Booking scheduled = bookingRepository.save(Booking.builder()
                .user(customer)
                .service(service)
                .bookingType(BookingType.SCHEDULED)
                .status(BookingStage.REQUESTED)
                .pinCode("4821")
                .scheduledAt(Instant.now().plus(2, ChronoUnit.DAYS))
                .userLat(MADHAPUR_LAT)
                .userLng(MADHAPUR_LNG)
                .baseAmount(new BigDecimal("199.00"))
                .totalAmount(new BigDecimal("199.00"))
                .paymentMethod(PaymentMode.UPI)
                .servicePincode(MADHAPUR_PINCODE)
                .build());
        transactionTemplate.executeWithoutResult(status -> domainEventService.recordEvent(
                scheduled, BookingEventType.BOOKING_CREATED, null, BookingStage.REQUESTED,
                BookingActorType.CUSTOMER, customer.getUserId()));
        pushes.clear();

        dispatchConsumer.consumeBookingEvent(record(scheduled.getBookingId(), outboxPayload(scheduled.getBookingId())));

        assertThat(count("SELECT count(*) FROM booking_assignment")).isZero();
        assertThat(bookingStatus(scheduled.getBookingId())).isEqualTo("REQUESTED");
        assertThat(pushes).isEmpty();
    }

    // ================================================================================================================
    // 5. Booking state: no workers, rejection, timeout, cancellation, full lifecycle
    // ================================================================================================================

    @Test
    @DisplayName("No nearby workers: WORKER_NOT_FOUND is committed, the customer is told after commit, and retry-dispatch works once a worker appears")
    void noEligibleWorker_thenRetryDispatch() {
        User customer = newCustomer("Madhapur customer");

        BookingResponse response = book(customer);
        UUID bookingId = response.getBookingId();

        assertThat(response.getStatus()).isEqualTo(BookingStage.WORKER_NOT_FOUND);
        assertThat(bookingStatus(bookingId)).isEqualTo("WORKER_NOT_FOUND");
        assertThat(count("SELECT count(*) FROM booking_assignment")).isZero();
        assertThat(jdbc.queryForObject("SELECT assignment_status FROM dispatch_audit_logs WHERE booking_id = ?", String.class, bookingId))
                .isEqualTo("WORKER_NOT_FOUND");
        assertThat(pushes).as("customer: STOMP booking topic + STOMP customer topic + FCM").hasSize(PUSHES_PER_CUSTOMER_NOTIFICATION);
        assertThat(pushes).allSatisfy(p -> assertThat(p.committedBookingStatuses()).isEqualTo("WORKER_NOT_FOUND"));

        // a partner comes online; the customer taps "retry" (BookingController.retryDispatch: reset to REQUESTED, dispatch by id)
        Worker late = newOnlineWorker("Hitec City electrician", HITEC_CITY_LAT, HITEC_CITY_LNG, MADHAPUR_PINCODE);
        pushes.clear();
        Booking stuck = bookingRepository.findById(bookingId).orElseThrow();
        stuck.setStatus(BookingStage.REQUESTED);
        bookingRepository.save(stuck);
        assignmentService.dispatchInstantBookingById(bookingId, subService.getSubServiceId());

        assertThat(bookingStatus(bookingId)).isEqualTo("SEARCHING");
        assertThat(assignmentStatus(bookingId, late)).isEqualTo("PENDING");
        assertThat(pushes).hasSize(PUSHES_PER_WORKER_ALERT)
                .allSatisfy(p -> assertThat(p.committedAssignments()).isGreaterThanOrEqualTo(1));
        assertThat(assignmentService.getPendingRequestsForWorker(late.getUser().getUserId())).hasSize(1);
    }

    @Test
    @DisplayName("Rejection: the batch cascades only when the LAST outstanding offer is rejected, and it widens the radius to reach untried workers")
    void rejection_cascadesToTheNextBatch_andWidensTheRadius() {
        Worker near = newOnlineWorker("Hitec City electrician", HITEC_CITY_LAT, HITEC_CITY_LNG, MADHAPUR_PINCODE);
        Worker mid = newOnlineWorker("Kondapur electrician", KONDAPUR_LAT, KONDAPUR_LNG, MADHAPUR_PINCODE);
        Worker far = newOnlineWorker("Banjara Hills electrician", BANJARA_HILLS_LAT, BANJARA_HILLS_LNG, null);
        UUID bookingId = book(newCustomer("Madhapur customer")).getBookingId();

        assertThat(count("SELECT count(*) FROM booking_assignment WHERE booking_id = ?", bookingId)).as("first batch = 2 nearest").isEqualTo(2);
        assertThat(assignmentService.getPendingRequestsForWorker(far.getUser().getUserId())).isEmpty();

        assignmentService.handleWorkerRejection(bookingId, near.getUser().getUserId(), null);
        assertThat(count("SELECT count(*) FROM booking_assignment WHERE booking_id = ?", bookingId))
                .as("another offer is still outstanding: no cascade yet").isEqualTo(2);
        assertThat(bookingStatus(bookingId)).isEqualTo("SEARCHING");

        pushes.clear();
        assignmentService.handleWorkerRejection(bookingId, mid.getUser().getUserId(), null);
        assertThat(assignmentStatus(bookingId, far)).as("5 km tier only holds tried workers; the 10 km tier reaches the next one")
                .isEqualTo("PENDING");
        assertThat(pushes).as("the new worker is alerted (4 STOMP + FCM), after commit").hasSize(PUSHES_PER_WORKER_ALERT)
                .allSatisfy(p -> assertThat(p.committedAssignments()).isEqualTo(3));
        assertThat(assignmentService.getPendingRequestsForWorker(far.getUser().getUserId())).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT attempt_no FROM booking_assignment WHERE booking_id = ? AND worker_id = ?",
                Integer.class, bookingId, far.getWorkerId())).isEqualTo(2);

        pushes.clear();
        assignmentService.handleWorkerRejection(bookingId, far.getUser().getUserId(), null);
        assertThat(bookingStatus(bookingId)).as("everyone has been asked").isEqualTo("WORKER_NOT_FOUND");
        assertThat(pushes).as("customer told after commit").hasSize(PUSHES_PER_CUSTOMER_NOTIFICATION)
                .allSatisfy(p -> assertThat(p.committedBookingStatuses()).isEqualTo("WORKER_NOT_FOUND"));
    }

    @Test
    @DisplayName("Two workers rejecting at the same moment never strand the booking in SEARCHING")
    void concurrentRejections_neverStrandTheBooking() throws Exception {
        for (int round = 1; round <= 3; round++) {
            resetDatabase();
            seedCatalog();
            Worker a = newOnlineWorker("Hitec City electrician " + round, HITEC_CITY_LAT, HITEC_CITY_LNG, MADHAPUR_PINCODE);
            Worker b = newOnlineWorker("Kondapur electrician " + round, KONDAPUR_LAT, KONDAPUR_LNG, MADHAPUR_PINCODE);
            UUID bookingId = book(newCustomer("Madhapur customer " + round)).getBookingId();

            // Make the race certain instead of lucky: every offer update takes 400 ms inside the database, so both
            // rejections are guaranteed to be in flight together. Without the booking row lock each of them then
            // still sees the other's offer as PENDING, neither cascades, and the booking is stranded in SEARCHING.
            slowDownAssignmentUpdates();
            try {
                runConcurrently(
                        () -> { assignmentService.handleWorkerRejection(bookingId, a.getUser().getUserId(), null); return true; },
                        () -> { assignmentService.handleWorkerRejection(bookingId, b.getUser().getUserId(), null); return true; });
            } finally {
                removeAssignmentUpdateDelay();
            }

            assertThat(count("SELECT count(*) FROM booking_assignment WHERE booking_id = ? AND status = 'PENDING'", bookingId))
                    .as("round " + round + ": no offer left hanging").isZero();
            assertThat(bookingStatus(bookingId))
                    .as("round " + round + ": every worker declined and nobody else exists, so the customer must be told")
                    .isEqualTo("WORKER_NOT_FOUND");
        }
    }

    @Test
    @DisplayName("Open-in-view: a booking another transaction has already moved on is skipped cleanly, not dispatched from a stale cached copy")
    void dispatchById_underOpenEntityManagerInView_neverActsOnAStaleCopy() {
        Worker worker = newOnlineWorker("Hitec City electrician", HITEC_CITY_LAT, HITEC_CITY_LNG, MADHAPUR_PINCODE);
        // leave a booking REQUESTED with no offers: a real database failure makes the first dispatch roll back
        failAssignmentInsertsFor(worker);
        UUID bookingId;
        try {
            bookingId = book(newCustomer("Madhapur customer")).getBookingId();
        } finally {
            removeAssignmentFailure();
        }
        assertThat(bookingStatus(bookingId)).isEqualTo("REQUESTED");

        AtomicReference<Throwable> failure = new AtomicReference<>();
        inRequest(() -> {
            // the request has already read the booking, so its copy lives in the open EntityManager ...
            bookingRepository.findById(bookingId).orElseThrow();
            // ... and meanwhile somebody else (customer cancel, another instance's dispatch, ...) commits a change
            jdbc.update("UPDATE bookings SET status = 'CANCELLED', version = version + 1 WHERE booking_id = ?", bookingId);
            try {
                assignmentService.dispatchInstantBookingById(bookingId, null);
            } catch (Throwable t) {
                failure.set(t);
            }
        });

        assertThat(failure.get()).as("a booking that is no longer waiting is skipped, not an error").isNull();
        assertThat(count("SELECT count(*) FROM booking_assignment WHERE booking_id = ?", bookingId)).isZero();
        assertThat(bookingStatus(bookingId)).isEqualTo("CANCELLED");
        assertThat(pushes).as("nobody is told about a cancelled booking").isEmpty();
    }

    @Test
    @DisplayName("Retry after every nearby worker already had (and ignored) the offer ends in WORKER_NOT_FOUND, never a SEARCHING booking nobody is looking for")
    void retryWhenEveryEligibleWorkerWasAlreadyOffered_isNotLeftSearching() {
        Worker only = newOnlineWorker("Hitec City electrician", HITEC_CITY_LAT, HITEC_CITY_LNG, MADHAPUR_PINCODE);
        UUID bookingId = book(newCustomer("Madhapur customer")).getBookingId();
        expireOffers(bookingId);
        assignmentService.processExpiredAssignments();
        assertThat(bookingStatus(bookingId)).as("the only worker timed out").isEqualTo("WORKER_NOT_FOUND");
        assertThat(assignmentStatus(bookingId, only)).isEqualTo("TIMEOUT");
        pushes.clear();

        // the customer presses "try again" (the retry endpoint resets the booking to REQUESTED, then dispatches): the same
        // single worker is still the only candidate, and was already offered
        jdbc.update("UPDATE bookings SET status = 'REQUESTED' WHERE booking_id = ?", bookingId);
        assignmentService.dispatchInstantBookingById(bookingId, null);

        assertThat(bookingStatus(bookingId)).as("nobody new to offer it to: the customer must be told, not left waiting")
                .isEqualTo("WORKER_NOT_FOUND");
        assertThat(count("SELECT count(*) FROM booking_assignment WHERE booking_id = ?", bookingId))
                .as("the worker is not offered the same booking twice").isEqualTo(1);
    }

    @Test
    @DisplayName("An offer expiring at the very moment its worker accepts: no deadlock, and the outcome is consistent whoever wins")
    void expirySweeper_racingAnAcceptance_neverDeadlocks() throws Exception {
        for (int round = 1; round <= 3; round++) {
            resetDatabase();
            seedCatalog();
            Worker a = newOnlineWorker("Hitec City electrician " + round, HITEC_CITY_LAT, HITEC_CITY_LNG, MADHAPUR_PINCODE);
            Worker b = newOnlineWorker("Kondapur electrician " + round, KONDAPUR_LAT, KONDAPUR_LNG, MADHAPUR_PINCODE);
            UUID bookingId = book(newCustomer("Madhapur customer " + round)).getBookingId();
            expireOffers(bookingId);

            // Every offer update takes 400 ms inside the database, so the sweeper is certainly still busy with the
            // offers when the acceptance (which locks the booking first, then the offer) starts. A sweeper that
            // touches the offers before the booking takes the locks in the opposite order and deadlocks with it.
            slowDownAssignmentUpdates();
            List<Boolean> results;
            try {
                results = runConcurrently(
                        () -> { assignmentService.processExpiredAssignments(); return true; },
                        () -> { Thread.sleep(150); return accept(bookingId, a); });
            } finally {
                removeAssignmentUpdateDelay();
            }

            boolean accepted = results.get(1);
            if (accepted) {
                assertThat(bookingStatus(bookingId)).as("round " + round + ": accepted").isEqualTo("ACCEPTED");
                assertThat(assignmentStatus(bookingId, a)).as("round " + round + ": the winner's offer says so").isEqualTo("ACCEPTED");
            } else {
                assertThat(bookingStatus(bookingId)).as("round " + round + ": the sweeper won, nobody else exists")
                        .isEqualTo("WORKER_NOT_FOUND");
            }
            assertThat(count("SELECT count(*) FROM booking_assignment WHERE booking_id = ? AND status = 'PENDING'", bookingId))
                    .as("round " + round + ": no offer left hanging").isZero();
        }
    }

    @Test
    @DisplayName("Timeout: unanswered offers are marked TIMEOUT, the next batch is offered, then WORKER_NOT_FOUND; scored (wave) offers are left to the wave scanner")
    void timeout_cascadesToTheNextBatch_andLeavesWaveOffersToTheWaveScanner() {
        Worker near = newOnlineWorker("Hitec City electrician", HITEC_CITY_LAT, HITEC_CITY_LNG, MADHAPUR_PINCODE);
        Worker mid = newOnlineWorker("Kondapur electrician", KONDAPUR_LAT, KONDAPUR_LNG, MADHAPUR_PINCODE);
        Worker far = newOnlineWorker("Banjara Hills electrician", BANJARA_HILLS_LAT, BANJARA_HILLS_LNG, null);
        UUID bookingId = book(newCustomer("Madhapur customer")).getBookingId();

        // ---- an offer carrying a score belongs to the wave engine: this sweeper must not touch it -------------
        expireOffers(bookingId);
        jdbc.update("UPDATE booking_assignment SET score = 0.9 WHERE booking_id = ? AND worker_id = ?", bookingId, near.getWorkerId());
        pushes.clear();
        assignmentService.processExpiredAssignments();
        assertThat(assignmentStatus(bookingId, near)).as("wave-engine offer left alone").isEqualTo("PENDING");
        assertThat(assignmentStatus(bookingId, mid)).isEqualTo("TIMEOUT");
        assertThat(bookingStatus(bookingId)).as("an offer is still outstanding: no cascade").isEqualTo("SEARCHING");
        assertThat(pushes).isEmpty();

        // ---- now it is an ordinary unanswered offer ----------------------------------------------------------------
        jdbc.update("UPDATE booking_assignment SET score = NULL WHERE booking_id = ?", bookingId);
        assignmentService.processExpiredAssignments();
        assertThat(assignmentStatus(bookingId, near)).isEqualTo("TIMEOUT");
        assertThat(assignmentStatus(bookingId, far)).as("next batch (10 km tier)").isEqualTo("PENDING");
        assertThat(bookingStatus(bookingId)).isEqualTo("SEARCHING");
        assertThat(pushes).as("far worker alerted: 4 STOMP + FCM").hasSize(PUSHES_PER_WORKER_ALERT)
                .allSatisfy(p -> assertThat(p.committedAssignments()).isEqualTo(3));

        pushes.clear();
        expireOffers(bookingId);
        assignmentService.processExpiredAssignments();
        assertThat(assignmentStatus(bookingId, far)).isEqualTo("TIMEOUT");
        assertThat(bookingStatus(bookingId)).isEqualTo("WORKER_NOT_FOUND");
        assertThat(pushes).as("customer told after commit").hasSize(PUSHES_PER_CUSTOMER_NOTIFICATION)
                .allSatisfy(p -> assertThat(p.committedBookingStatuses()).isEqualTo("WORKER_NOT_FOUND"));

        // a second sweep finds nothing left to do
        pushes.clear();
        assignmentService.processExpiredAssignments();
        assertThat(pushes).isEmpty();
    }

    @Test
    @DisplayName("Cancellation while SEARCHING: workers no longer see the request, a late accept is refused and expired offers never cascade")
    void cancellation_whileSearching_stopsEverything() {
        Worker a = newOnlineWorker("Hitec City electrician", HITEC_CITY_LAT, HITEC_CITY_LNG, MADHAPUR_PINCODE);
        Worker b = newOnlineWorker("Kondapur electrician", KONDAPUR_LAT, KONDAPUR_LNG, MADHAPUR_PINCODE);
        newOnlineWorker("Banjara Hills electrician", BANJARA_HILLS_LAT, BANJARA_HILLS_LNG, null);
        User customer = newCustomer("Madhapur customer");
        UUID bookingId = book(customer).getBookingId();
        assertThat(assignmentService.getPendingRequestsForWorker(a.getUser().getUserId())).hasSize(1);

        cancellationService.cancelBooking(bookingId, customer.getUserId(), "changed my mind");

        assertThat(bookingStatus(bookingId)).isEqualTo("CANCELLED");
        assertThat(assignmentService.getPendingRequestsForWorker(a.getUser().getUserId())).isEmpty();
        assertThat(assignmentService.getPendingRequestsForWorker(b.getUser().getUserId())).isEmpty();
        assertThatThrownBy(() -> assignmentService.handleWorkerAcceptance(bookingId, a.getUser().getUserId()))
                .isInstanceOf(IllegalStateException.class);

        pushes.clear();
        expireOffers(bookingId);
        assignmentService.processExpiredAssignments();
        assertThat(bookingStatus(bookingId)).as("a cancelled booking is never revived").isEqualTo("CANCELLED");
        assertThat(count("SELECT count(*) FROM booking_assignment WHERE booking_id = ?", bookingId)).isEqualTo(2);
        assertThat(pushes).isEmpty();
        assertThat(recoveryJob.recoverUndispatchedInstantBookings()).isZero();
    }

    @Test
    @DisplayName("Full lifecycle: REQUESTED -> SEARCHING -> ACCEPTED -> ON_THE_WAY -> ARRIVED -> IN_PROGRESS -> COMPLETED, each step committed")
    void fullLifecycle_everyTransitionIsPersisted() {
        Worker worker = newOnlineWorker("Hitec City electrician", HITEC_CITY_LAT, HITEC_CITY_LNG, MADHAPUR_PINCODE);
        User customer = newCustomer("Madhapur customer");
        UUID workerUserId = worker.getUser().getUserId();

        AtomicReference<String> pin = new AtomicReference<>();
        doAnswer(inv -> {
            String key = inv.getArgument(0);
            if (key.startsWith("booking:customer_pin:")) {
                pin.set(inv.getArgument(1));
            }
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        BookingResponse created = book(customer);
        UUID bookingId = created.getBookingId();
        assertThat(created.getStatus()).isEqualTo(BookingStage.SEARCHING);
        assertThat(bookingStatus(bookingId)).isEqualTo("SEARCHING");

        inRequest(() -> assignmentService.handleWorkerAcceptance(bookingId, workerUserId));
        assertThat(bookingStatus(bookingId)).isEqualTo("ACCEPTED");

        when(trackingService.startTravelling(eq(bookingId), eq(workerUserId))).thenReturn(
                com.homeease.backend.dto.TrackingDto.TrackingSessionDto.builder()
                        .bookingId(bookingId)
                        .workerUserId(workerUserId)
                        .trackingActive(true)
                        .build());
        inRequest(() -> assignmentService.handleWorkerStartTravel(bookingId, workerUserId));
        assertThat(bookingStatus(bookingId)).isEqualTo("ON_THE_WAY");

        inRequest(() -> assignmentService.handleWorkerArrival(bookingId, workerUserId));
        assertThat(bookingStatus(bookingId)).isEqualTo("ARRIVED");
        assertThat(pin.get()).as("the customer's 4-digit start PIN was issued").matches("\\d{4}");

        inRequest(() -> {
            Booking booking = bookingRepository.findById(bookingId).orElseThrow();
            bookingOtpService.verifyOtp(booking, workerUserId, pin.get());
            Worker assigned = workerRepository.findByUser_UserId(workerUserId).orElseThrow();
            sessionService.startSession(booking, assigned);
        });
        assertThat(bookingStatus(bookingId)).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT status FROM service_session WHERE booking_id = ?", String.class, bookingId)).isEqualTo("ACTIVE");

        inRequest(() -> sessionService.completeSession(bookingRepository.findById(bookingId).orElseThrow(), workerUserId));
        assertThat(bookingStatus(bookingId)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT status FROM service_session WHERE booking_id = ?", String.class, bookingId)).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM payments WHERE booking_id = ?", bookingId)).isEqualTo(1);

        // Domain events actually emitted (Kafka outbox). ON_THE_WAY and ARRIVED emit none today: documented, not changed.
        assertThat(jdbc.queryForList("SELECT event_type FROM booking_event_outbox WHERE aggregate_id = ?", String.class, bookingId))
                .containsExactlyInAnyOrder("BOOKING_CREATED", "PARTNER_ACCEPTED", "SERVICE_STARTED", "SERVICE_COMPLETED");
    }

    @Test
    @DisplayName("Reassignment: the previous worker is dropped and the next one is offered the job after the commit; it can no longer self-deadlock")
    void reassignment_dropsThePreviousWorker_offersTheNextOne_andNeverDeadlocks() {
        Worker a = newOnlineWorker("Hitec City electrician", HITEC_CITY_LAT, HITEC_CITY_LNG, MADHAPUR_PINCODE);
        Worker b = newOnlineWorker("Kondapur electrician", KONDAPUR_LAT, KONDAPUR_LNG, MADHAPUR_PINCODE);
        Worker c = newOnlineWorker("Gachibowli electrician", GACHIBOWLI_LAT, GACHIBOWLI_LNG, MADHAPUR_PINCODE);
        UUID bookingId = book(newCustomer("Madhapur customer")).getBookingId();   // batch of 2: offered to a and b
        assertThat(count("SELECT count(*) FROM booking_assignment WHERE booking_id = ?", bookingId)).isEqualTo(2);
        assignmentService.handleWorkerAcceptance(bookingId, a.getUser().getUserId());
        pushes.clear();

        // The watchdog fires: the accepted worker went silent. The transaction holds the booking row lock, and the
        // dispatch used to open a SECOND transaction on that same row (REQUIRES_NEW), which could never proceed.
        // The preemptive timeout turns such a hang into a test failure.
        assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
                reassignmentService.triggerReassignment(bookingId, "STALE_GPS_TELEMETRY",
                        "no GPS for 15 minutes", "TELEMETRY_WATCHDOG"));

        assertThat(jdbc.queryForObject("SELECT worker_id FROM bookings WHERE booking_id = ?", UUID.class, bookingId)).isNull();
        assertThat(bookingStatus(bookingId)).isEqualTo("SEARCHING");
        assertThat(assignmentStatus(bookingId, a)).isEqualTo("DROPPED");
        assertThat(assignmentStatus(bookingId, b)).as("already declined/dropped earlier: not asked again").isEqualTo("DROPPED");
        assertThat(assignmentStatus(bookingId, c)).as("the next, never-asked worker").isEqualTo("PENDING");
        assertThat(count("SELECT count(*) FROM booking_event_outbox WHERE aggregate_id = ? AND event_type = 'PARTNER_REASSIGNED'", bookingId))
                .isEqualTo(1);
        assertThat(pushes).as("worker c alerted (4 STOMP + FCM), after the commit").hasSize(PUSHES_PER_WORKER_ALERT)
                .allSatisfy(p -> assertThat(p.committedAssignments()).isEqualTo(3));
        assertThat(assignmentService.getPendingRequestsForWorker(c.getUser().getUserId())).hasSize(1);
    }

    // ================================================================================================================
    // 6. Worker matching (pincode, Redis GEO fallback, online, heartbeat, GPS freshness, skills, serviceability)
    // ================================================================================================================

    @Test
    @DisplayName("Redis GEO hit: a worker found in the GEO index is dispatched at the first radius tier")
    void redisGeoHit_isUsedForMatching() {
        // Redis holds a fresher position than the database row (which is ~11 km away): only the GEO path can find him
        Worker driving = newOnlineWorker("Secunderabad electrician", SECUNDERABAD_LAT, SECUNDERABAD_LNG, null);
        when(geoOperations.radius(eq("active_workers_geo"), any(Circle.class))).thenReturn(geoHit(driving, HITEC_CITY_LAT, HITEC_CITY_LNG));

        UUID bookingId = book(newCustomer("Madhapur customer")).getBookingId();

        assertThat(assignmentStatus(bookingId, driving)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT search_radius_meters FROM dispatch_audit_logs WHERE booking_id = ?", Double.class, bookingId))
                .as("found at the first (5 km) tier through Redis").isEqualTo(5000.0);
    }

    @Test
    @DisplayName("Redis unavailable: matching falls back to the database with the real positions, and never invents availability")
    void redisUnavailable_fallsBackToTheDatabase() {
        Worker distant = newOnlineWorker("Secunderabad electrician", SECUNDERABAD_LAT, SECUNDERABAD_LNG, null);
        when(geoOperations.radius(anyString(), any(Circle.class))).thenThrow(new RedisConnectionFailureException("Redis is down"));

        UUID bookingId = book(newCustomer("Madhapur customer")).getBookingId();

        assertThat(assignmentStatus(bookingId, distant)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT search_radius_meters FROM dispatch_audit_logs WHERE booking_id = ?", Double.class, bookingId))
                .as("database fallback expanded 5 -> 10 -> 15 km before reaching the ~11 km worker").isEqualTo(15000.0);

        // and with nobody in the database either, Redis being down does not conjure a worker
        resetDatabase();
        seedCatalog();
        BookingResponse none = book(newCustomer("Another Madhapur customer"));
        assertThat(none.getStatus()).isEqualTo(BookingStage.WORKER_NOT_FOUND);
        assertThat(count("SELECT count(*) FROM booking_assignment")).isZero();
    }

    @Test
    @DisplayName("Eligibility rules on real data: online, verified, heartbeat, GPS freshness, skill, pincode, busy")
    void matchingRules_areEnforcedAgainstRealRows() {
        inRequest(() -> {
            // offline / unverified
            Worker offline = newOnlineWorker("offline", HITEC_CITY_LAT, HITEC_CITY_LNG, MADHAPUR_PINCODE);
            offline.setIsOnline(false);
            workerRepository.save(offline);
            Worker unverified = newOnlineWorker("unverified", HITEC_CITY_LAT, HITEC_CITY_LNG, MADHAPUR_PINCODE);
            unverified.setIsVerified(false);
            workerRepository.save(unverified);
            assertThat(reason(offline)).isEqualTo("WORKER_OFFLINE");
            assertThat(reason(unverified)).isEqualTo("WORKER_NOT_APPROVED");

            // heartbeat: a worker whose heartbeat key expired (presence gate) is not dispatchable
            Worker silent = newOnlineWorker("silent", HITEC_CITY_LAT, HITEC_CITY_LNG, MADHAPUR_PINCODE);
            when(presenceService.isDispatchable(silent)).thenReturn(false);
            assertThat(reason(silent)).isEqualTo("HEARTBEAT_EXPIRED");

            // GPS freshness: same-pincode workers get 24 h of grace, everyone else 30 minutes
            Worker samePincodeThreeHoursOld = newOnlineWorker("3h old, same pincode", HITEC_CITY_LAT, HITEC_CITY_LNG, MADHAPUR_PINCODE);
            setLastLocationAge(samePincodeThreeHoursOld, ChronoUnit.HOURS, 3);
            assertThat(reason(samePincodeThreeHoursOld)).as("fresh enough for its own pincode").isNull();
            Worker samePincodeTwoDaysOld = newOnlineWorker("2d old, same pincode", HITEC_CITY_LAT, HITEC_CITY_LNG, MADHAPUR_PINCODE);
            setLastLocationAge(samePincodeTwoDaysOld, ChronoUnit.DAYS, 2);
            assertThat(reason(samePincodeTwoDaysOld)).isEqualTo("LOCATION_STALE");
            Worker roamingFortyFiveMinutesOld = newOnlineWorker("45m old, no pincode", HITEC_CITY_LAT, HITEC_CITY_LNG, null);
            setLastLocationAge(roamingFortyFiveMinutesOld, ChronoUnit.MINUTES, 45);
            assertThat(reason(roamingFortyFiveMinutesOld)).isEqualTo("LOCATION_STALE");
            Worker noGps = newOnlineWorker("no gps", HITEC_CITY_LAT, HITEC_CITY_LNG, MADHAPUR_PINCODE);
            noGps.setCurrentLat(null);
            noGps.setCurrentLng(null);
            workerRepository.save(noGps);
            assertThat(reason(noGps)).isEqualTo("LOCATION_MISSING");

            // skill: no link to this service at all, versus a link to a DIFFERENT sub-service of the same service
            Worker plumber = newWorkerWithoutSkill("plumber", HITEC_CITY_LAT, HITEC_CITY_LNG, MADHAPUR_PINCODE);
            assertThat(reason(plumber)).isEqualTo("SERVICE_NOT_SUPPORTED");
            SubService otherSubService = subServiceRepository.save(SubService.builder()
                    .service(service).name("Switchboard repair").basePrice(new BigDecimal("249.00")).estimatedMins(45)
                    .imageUrl("https://example.invalid/switch.png").isActive(true).build());
            Worker sameVertical = newWorkerWithoutSkill("same vertical", HITEC_CITY_LAT, HITEC_CITY_LNG, MADHAPUR_PINCODE);
            workerServiceLinkRepository.save(WorkerServiceLink.builder()
                    .id(new WorkerServiceId(sameVertical.getWorkerId(), otherSubService.getSubServiceId()))
                    .worker(sameVertical).subService(otherSubService).build());
            assertThat(reason(sameVertical)).as("qualifies through the service vertical").isNull();

            // pincode: works only in another pincode
            Worker elsewhere = newOnlineWorker("works in Secunderabad", HITEC_CITY_LAT, HITEC_CITY_LNG, "500003");
            assertThat(reason(elsewhere)).isEqualTo("PINCODE_NOT_SERVICED");

            // busy: already has an accepted job in progress
            Worker busy = newOnlineWorker("busy", HITEC_CITY_LAT, HITEC_CITY_LNG, MADHAPUR_PINCODE);
            User someCustomer = newCustomer("someone else");
            bookingRepository.save(Booking.builder().user(someCustomer).worker(busy).service(service)
                    .bookingType(BookingType.INSTANT).status(BookingStage.IN_PROGRESS).pinCode("1234")
                    .scheduledAt(Instant.now()).userLat(MADHAPUR_LAT).userLng(MADHAPUR_LNG)
                    .baseAmount(BigDecimal.TEN).totalAmount(BigDecimal.TEN).paymentMethod(PaymentMode.UPI)
                    .servicePincode(MADHAPUR_PINCODE).build());
            assertThat(reason(busy)).isEqualTo("WORKER_BUSY");
        });
    }

    // ================================================================================================================
    // 7. Cart checkout (same orchestration)
    // ================================================================================================================

    @Test
    @DisplayName("Cart checkout: booking committed first, offers persisted afterwards, cart cleared, response SEARCHING")
    void cartCheckout_persistsOffersAfterTheBookingCommit() {
        Worker worker = newOnlineWorker("Hitec City electrician", HITEC_CITY_LAT, HITEC_CITY_LNG, MADHAPUR_PINCODE);
        User customer = newCustomer("Madhapur customer");
        BookingCart cart = cartRepository.save(BookingCart.builder().user(customer).build());
        cartItemRepository.save(BookingCartItem.builder()
                .cart(cart).service(service).subService(subService).quantity(1).unitPrice(new BigDecimal("199.00")).build());

        BookingResponse response = cartCheckoutService.checkout(customer.getUserId(), CheckoutFromCartRequest.builder()
                .bookingType("INSTANT")
                .userLat(MADHAPUR_LAT)
                .userLng(MADHAPUR_LNG)
                .address("Madhapur, Hyderabad")
                .pincode(MADHAPUR_PINCODE)
                .paymentMethod(PaymentMode.UPI)
                .idempotencyKey(UUID.randomUUID().toString())
                .build());

        UUID bookingId = response.getBookingId();
        assertThat(response.getStatus()).isEqualTo(BookingStage.SEARCHING);
        assertThat(bookingStatus(bookingId)).isEqualTo("SEARCHING");
        assertThat(count("SELECT count(*) FROM bookings")).isEqualTo(1);
        assertThat(assignmentStatus(bookingId, worker)).isEqualTo("PENDING");
        assertThat(count("SELECT count(*) FROM booking_cart_items WHERE cart_id = ?", cart.getCartId())).as("cart cleared").isZero();
        assertThat(count("SELECT count(*) FROM booking_event_outbox WHERE aggregate_id = ? AND event_type = 'BOOKING_CREATED'", bookingId))
                .isEqualTo(1);
        assertThat(pushes).hasSize(PUSHES_PER_WORKER_ALERT)
                .allSatisfy(p -> assertThat(p.committedAssignments()).isGreaterThanOrEqualTo(1));
        assertThat(assignmentService.getPendingRequestsForWorker(worker.getUser().getUserId())).hasSize(1);
    }

    @Test
    @DisplayName("The recovery job's defaults are really bound from application.yml (keys under homeease.dispatch.recovery)")
    void recoveryJob_defaultsComeFromApplicationYml() {
        assertThat(environment.getProperty("homeease.dispatch.recovery.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("homeease.dispatch.recovery.interval-ms")).isEqualTo("30000");
        assertThat(environment.getProperty("homeease.dispatch.recovery.min-age-seconds")).isEqualTo("45");
        assertThat(environment.getProperty("homeease.dispatch.recovery.max-age-minutes")).isEqualTo("15");
        assertThat(environment.getProperty("homeease.dispatch.recovery.batch-size")).isEqualTo("20");
    }

    // ================================================================================================================
    // helpers
    // ================================================================================================================

    BookingResponse book(User customer) {
        return instantBookingService.createInstantBooking(customer.getUserId(), instantRequest(), UUID.randomUUID().toString());
    }

    InstantBookingRequest instantRequest() {
        return InstantBookingRequest.builder()
                .serviceId(service.getServiceId())
                .subServices(List.of(SubServiceRequestItem.builder().subServiceId(subService.getSubServiceId()).quantity(1).build()))
                .userLat(MADHAPUR_LAT)
                .userLng(MADHAPUR_LNG)
                .paymentMethod(PaymentMode.UPI)
                .pincode(MADHAPUR_PINCODE)
                .build();
    }

    User newCustomer(String label) {
        return userRepository.save(User.builder()
                .fullName(label)
                .phoneNumber("+91" + PHONE_SEQ.incrementAndGet())
                .email("customer." + PHONE_SEQ.get() + "@it.invalid")
                .role(UserRole.CUSTOMER)
                .build());
    }

    Worker newOnlineWorker(String label, double lat, double lng, String workingPincodes) {
        Worker worker = newWorkerWithoutSkill(label, lat, lng, workingPincodes);
        workerServiceLinkRepository.save(WorkerServiceLink.builder()
                .id(new WorkerServiceId(worker.getWorkerId(), subService.getSubServiceId()))
                .worker(worker)
                .subService(subService)
                .build());
        return worker;
    }

    Worker newWorkerWithoutSkill(String label, double lat, double lng, String workingPincodes) {
        User user = userRepository.save(User.builder()
                .fullName(label)
                .phoneNumber("+91" + PHONE_SEQ.incrementAndGet())
                .email("worker." + PHONE_SEQ.get() + "@it.invalid")
                .role(UserRole.WORKER)
                .build());
        return workerRepository.save(Worker.builder()
                .user(user)
                .kycStatus("APPROVED")
                .isOnline(true)
                .isVerified(true)
                .currentLat(lat)
                .currentLng(lng)
                .lastLocationUpdatedAt(Instant.now())
                .workingPincodes(workingPincodes)
                .build());
    }

    void setLastLocationAge(Worker worker, ChronoUnit unit, long amount) {
        worker.setLastLocationUpdatedAt(Instant.now().minus(amount, unit));
        workerRepository.save(worker);
    }

    /** Null when the worker is eligible for the Madhapur electrician job, otherwise the rejection reason. */
    String reason(Worker worker) {
        java.util.Map<String, String> reasons = new java.util.LinkedHashMap<>();
        boolean eligible = assignmentService.isCandidateEligible(
                worker, service.getServiceId(), subService.getSubServiceId(), MADHAPUR_PINCODE, reasons);
        return eligible ? null : reasons.get(worker.getWorkerId().toString());
    }

    boolean accept(UUID bookingId, Worker worker) {
        try {
            assignmentService.handleWorkerAcceptance(bookingId, worker.getUser().getUserId());
            return true;
        } catch (IllegalStateException lostTheRace) {
            return false;
        }
    }

    /** Starts every task at the same instant on its own thread and returns their results (an exception fails the test). */
    @SafeVarargs
    final <T> List<T> runConcurrently(Callable<T>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        try {
            CyclicBarrier startTogether = new CyclicBarrier(tasks.length);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    startTogether.await(10, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Runs {@code work} the way a web request does: an EntityManager stays open for the whole thing (open-in-view). */
    void inRequest(Runnable work) {
        EntityManager em = entityManagerFactory.createEntityManager();
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(em));
        try {
            work.run();
        } finally {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory);
            EntityManagerFactoryUtils.closeEntityManager(em);
        }
    }

    /** A real database failure: any attempt to insert an offer for this worker raises an error. */
    void failAssignmentInsertsFor(Worker worker) {
        jdbc.execute("CREATE OR REPLACE FUNCTION it_fail_assignment() RETURNS trigger AS $$ "
                + "BEGIN RAISE EXCEPTION 'integration test: injected failure'; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER it_fail_assignment_trg BEFORE INSERT ON booking_assignment FOR EACH ROW "
                + "WHEN (NEW.worker_id = '" + worker.getWorkerId() + "') EXECUTE FUNCTION it_fail_assignment()");
    }

    void removeAssignmentFailure() {
        jdbc.execute("DROP TRIGGER IF EXISTS it_fail_assignment_trg ON booking_assignment");
    }

    /** Every UPDATE of an offer takes 400 ms inside the database: widens a race window deterministically. */
    void slowDownAssignmentUpdates() {
        jdbc.execute("CREATE OR REPLACE FUNCTION it_slow_assignment_update() RETURNS trigger AS $$ "
                + "BEGIN PERFORM pg_sleep(0.4); RETURN NEW; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER it_slow_assignment_update_trg BEFORE UPDATE ON booking_assignment FOR EACH ROW "
                + "EXECUTE FUNCTION it_slow_assignment_update()");
    }

    void removeAssignmentUpdateDelay() {
        jdbc.execute("DROP TRIGGER IF EXISTS it_slow_assignment_update_trg ON booking_assignment");
    }

    void expireOffers(UUID bookingId) {
        jdbc.update("UPDATE booking_assignment SET expires_at = now() - interval '1 minute' WHERE booking_id = ? AND status = 'PENDING'", bookingId);
    }

    ConsumerRecord<String, String> record(UUID bookingId, String payload) {
        return new ConsumerRecord<>("firstdoor.booking.events.v1", 0, 0L, bookingId.toString(), payload);
    }

    String outboxPayload(UUID bookingId) {
        return jdbc.queryForObject("SELECT payload::text FROM booking_event_outbox WHERE aggregate_id = ? AND event_type = 'BOOKING_CREATED'",
                String.class, bookingId);
    }

    GeoResults<RedisGeoCommands.GeoLocation<String>> noGeoHits() {
        return new GeoResults<>(Collections.emptyList());
    }

    GeoResults<RedisGeoCommands.GeoLocation<String>> geoHit(Worker worker, double lat, double lng) {
        GeoResult<RedisGeoCommands.GeoLocation<String>> hit = new GeoResult<>(
                new RedisGeoCommands.GeoLocation<>(worker.getWorkerId().toString(), new Point(lng, lat)),
                new Distance(1.5, Metrics.KILOMETERS));
        return new GeoResults<>(List.of(hit));
    }

    int committedAssignments() {
        return count("SELECT count(*) FROM booking_assignment");
    }

    String committedBookingStatuses() {
        return String.join(",", jdbc.queryForList("SELECT status FROM bookings ORDER BY created_at", String.class));
    }

    int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    String bookingStatus(UUID bookingId) {
        return jdbc.queryForObject("SELECT status FROM bookings WHERE booking_id = ?", String.class, bookingId);
    }

    String assignmentStatus(UUID bookingId, Worker worker) {
        List<String> rows = jdbc.queryForList("SELECT status FROM booking_assignment WHERE booking_id = ? AND worker_id = ?",
                String.class, bookingId, worker.getWorkerId());
        assertThat(rows).as("assignment rows for worker " + worker.getWorkerId()).hasSize(1);
        return rows.get(0);
    }
}
