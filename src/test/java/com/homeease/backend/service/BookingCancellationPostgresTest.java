package com.homeease.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.homeease.backend.dto.BookingDto.BookingResponse;
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
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Atomic booking cancellation and completion-race verification against a REAL PostgreSQL
 * (zonky embedded-postgres, throw-away instance).
 */
@SpringBootTest(
        classes = BookingCancellationPostgresTest.TestConfig.class,
        properties = {
                "spring.jpa.hibernate.ddl-auto=create",
                "spring.jpa.open-in-view=false"
        })
class BookingCancellationPostgresTest {

    @Configuration
    @ImportAutoConfiguration({
            HibernateJpaAutoConfiguration.class,
            JpaRepositoriesAutoConfiguration.class,
            TransactionAutoConfiguration.class,
            JacksonAutoConfiguration.class})
    @EntityScan("com.homeease.backend.model.entity")
    @EnableJpaRepositories("com.homeease.backend.repository")
    @Import({BookingCancellationService.class, BookingDomainEventService.class})
    static class TestConfig {

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

    @MockBean BookingNotificationService bookingNotificationService;
    @MockBean RedisTemplate<String, String> redisTemplate;
    @MockBean TrackingService trackingService;

    @Autowired BookingCancellationService cancellationService;
    @Autowired BookingRepository bookingRepository;
    @Autowired UserRepository userRepository;
    @Autowired WorkerRepository workerRepository;
    @Autowired ServiceRepository serviceRepository;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;

    static final AtomicLong PHONE_SEQ = new AtomicLong(7_200_000_000L);

    JdbcTemplate jdbc;
    User customer;
    User workerUser;
    User stranger;
    Worker worker;
    ServiceEntity service;

    @BeforeEach
    void freshFixtures() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DO $$ DECLARE r RECORD; BEGIN "
                + "FOR r IN SELECT tablename FROM pg_tables WHERE schemaname = 'public' LOOP "
                + "EXECUTE 'TRUNCATE TABLE ' || quote_ident(r.tablename) || ' CASCADE'; END LOOP; END $$");

        customer = userRepository.save(User.builder()
                .fullName("Cancel customer").phoneNumber("+91" + PHONE_SEQ.incrementAndGet())
                .email("cc" + PHONE_SEQ.get() + "@it.invalid").role(UserRole.CUSTOMER).build());
        workerUser = userRepository.save(User.builder()
                .fullName("Cancel worker").phoneNumber("+91" + PHONE_SEQ.incrementAndGet())
                .email("cw" + PHONE_SEQ.get() + "@it.invalid").role(UserRole.WORKER).build());
        stranger = userRepository.save(User.builder()
                .fullName("Other customer").phoneNumber("+91" + PHONE_SEQ.incrementAndGet())
                .email("cs" + PHONE_SEQ.get() + "@it.invalid").role(UserRole.CUSTOMER).build());
        worker = workerRepository.save(Worker.builder()
                .user(workerUser).kycStatus("APPROVED").isOnline(true).isVerified(true).build());
        service = serviceRepository.save(ServiceEntity.builder()
                .name("AC Repair (fixture)").imageUrl("https://example.invalid/ac.png").isActive(true).build());
    }

    private Booking createBooking(BookingStage stage) {
        return bookingRepository.save(Booking.builder()
                .user(customer)
                .worker(worker)
                .service(service)
                .bookingType(BookingType.INSTANT)
                .status(stage)
                .pinCode("0000")
                .scheduledAt(Instant.now())
                .arrivedAt(stage == BookingStage.ARRIVED || stage == BookingStage.IN_PROGRESS || stage == BookingStage.COMPLETED ? Instant.now() : null)
                .userLat(17.4486)
                .userLng(78.3908)
                .baseAmount(new BigDecimal("299.00"))
                .totalAmount(new BigDecimal("299.00"))
                .paymentMethod(PaymentMode.COD)
                .createdAt(Instant.now())
                .build());
    }

    private String committedStatus(UUID bookingId) {
        return jdbc.queryForObject("SELECT status FROM bookings WHERE booking_id = ?", String.class, bookingId);
    }

    @Test
    @DisplayName("Cancelling ON_THE_WAY or ACCEPTED transitions to CANCELLED, stops tracking, and stays idempotent")
    void cancelBooking_allowsOnTheWayAndAccepted_andIsIdempotent() {
        Booking onTheWay = createBooking(BookingStage.ON_THE_WAY);
        UUID id1 = onTheWay.getBookingId();

        BookingResponse first = cancellationService.cancelBooking(id1, customer.getUserId(), "Changed plans");
        assertThat(String.valueOf(first.getStatus())).isEqualTo("CANCELLED");
        assertThat(committedStatus(id1)).isEqualTo("CANCELLED");
        verify(trackingService).stopTracking(eq(id1), anyString());

        BookingResponse second = cancellationService.cancelBooking(id1, customer.getUserId(), "Retry cancel");
        assertThat(String.valueOf(second.getStatus())).isEqualTo("CANCELLED");
        assertThat(committedStatus(id1)).isEqualTo("CANCELLED");

        Booking accepted = createBooking(BookingStage.ACCEPTED);
        UUID id2 = accepted.getBookingId();
        BookingResponse workerCancel = cancellationService.cancelBooking(id2, workerUser.getUserId(), "Worker unavailable");
        assertThat(String.valueOf(workerCancel.getStatus())).isEqualTo("CANCELLED");
        assertThat(committedStatus(id2)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("Unauthorized user cannot cancel another user's ON_THE_WAY booking")
    void cancelBooking_rejectsUnauthorizedCaller() {
        Booking onTheWay = createBooking(BookingStage.ON_THE_WAY);
        UUID bookingId = onTheWay.getBookingId();

        assertThatThrownBy(() -> cancellationService.cancelBooking(bookingId, stranger.getUserId(), "Hijack"))
                .isInstanceOf(RuntimeException.class);

        assertThat(committedStatus(bookingId)).isEqualTo("ON_THE_WAY");
    }

    @Test
    @DisplayName("directCancelBooking and cancelBooking never overwrite COMPLETED or IN_PROGRESS")
    void directCancelBooking_neverOverwritesCompletedOrInProgress() {
        Booking completed = createBooking(BookingStage.COMPLETED);
        UUID completedId = completed.getBookingId();
        Integer updatedCompleted = new TransactionTemplate(transactionManager).execute(tx ->
                bookingRepository.directCancelBooking(completedId, "Late cancel", customer, Instant.now()));
        assertThat(updatedCompleted).isZero();
        assertThat(committedStatus(completedId)).isEqualTo("COMPLETED");
        assertThatThrownBy(() -> cancellationService.cancelBooking(completedId, customer.getUserId(), "Late cancel"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(committedStatus(completedId)).isEqualTo("COMPLETED");

        Booking inProgress = createBooking(BookingStage.IN_PROGRESS);
        UUID inProgressId = inProgress.getBookingId();
        Integer updatedInProgress = new TransactionTemplate(transactionManager).execute(tx ->
                bookingRepository.directCancelBooking(inProgressId, "Mid-job cancel", customer, Instant.now()));
        assertThat(updatedInProgress).isZero();
        assertThat(committedStatus(inProgressId)).isEqualTo("IN_PROGRESS");
        assertThatThrownBy(() -> cancellationService.cancelBooking(inProgressId, customer.getUserId(), "Mid-job cancel"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(committedStatus(inProgressId)).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("Row-lock concurrency race: committing COMPLETED blocks and defeats concurrent cancel without overwriting")
    void concurrentRowLockRace_whenTxCommitsCompleted_cancelServiceNeverOverwritesCompleted() throws Exception {
        Booking arrived = createBooking(BookingStage.ARRIVED);
        UUID bookingId = arrived.getBookingId();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection locker = dataSource.getConnection()) {
            locker.setAutoCommit(false);
            try (PreparedStatement ps = locker.prepareStatement(
                    "UPDATE bookings SET status = 'COMPLETED', completed_at = now() WHERE booking_id = ?")) {
                ps.setObject(1, bookingId);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }

            CountDownLatch cancelStarted = new CountDownLatch(1);
            Future<Throwable> cancelFuture = pool.submit(() -> {
                cancelStarted.countDown();
                try {
                    cancellationService.cancelBooking(bookingId, customer.getUserId(), "Concurrent race");
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });

            assertThat(cancelStarted.await(2, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(250);
            assertThat(cancelFuture.isDone())
                    .as("cancel UPDATE must block on Postgres row lock until completion tx commits")
                    .isFalse();

            locker.commit();

            Throwable outcome = cancelFuture.get(5, TimeUnit.SECONDS);
            assertThat(outcome)
                    .as("concurrent cancel after COMPLETED commits must be rejected")
                    .isInstanceOf(IllegalStateException.class);
            assertThat(committedStatus(bookingId)).isEqualTo("COMPLETED");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Row-lock concurrency race: committing IN_PROGRESS blocks and defeats concurrent cancel without overwriting")
    void concurrentRowLockRace_whenTxCommitsInProgress_cancelServiceNeverOverwritesInProgress() throws Exception {
        Booking arrived = createBooking(BookingStage.ARRIVED);
        UUID bookingId = arrived.getBookingId();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection locker = dataSource.getConnection()) {
            locker.setAutoCommit(false);
            try (PreparedStatement ps = locker.prepareStatement(
                    "UPDATE bookings SET status = 'IN_PROGRESS', started_at = now() WHERE booking_id = ?")) {
                ps.setObject(1, bookingId);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }

            CountDownLatch cancelStarted = new CountDownLatch(1);
            Future<Throwable> cancelFuture = pool.submit(() -> {
                cancelStarted.countDown();
                try {
                    cancellationService.cancelBooking(bookingId, customer.getUserId(), "Concurrent race");
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });

            assertThat(cancelStarted.await(2, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(250);
            assertThat(cancelFuture.isDone())
                    .as("cancel UPDATE must block on Postgres row lock until IN_PROGRESS tx commits")
                    .isFalse();

            locker.commit();

            Throwable outcome = cancelFuture.get(5, TimeUnit.SECONDS);
            assertThat(outcome)
                    .as("concurrent cancel after IN_PROGRESS commits must be rejected")
                    .isInstanceOf(IllegalStateException.class);
            assertThat(committedStatus(bookingId)).isEqualTo("IN_PROGRESS");
        } finally {
            pool.shutdownNow();
        }
    }
}
