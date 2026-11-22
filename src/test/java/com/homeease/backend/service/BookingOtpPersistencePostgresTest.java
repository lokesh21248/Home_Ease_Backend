package com.homeease.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homeease.backend.model.entity.Booking;
import com.homeease.backend.model.entity.ServiceEntity;
import com.homeease.backend.model.entity.User;
import com.homeease.backend.model.entity.Worker;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.BookingType;
import com.homeease.backend.model.enums.PaymentMode;
import com.homeease.backend.model.enums.UserRole;
import com.homeease.backend.repository.BookingOtpRepository;
import com.homeease.backend.repository.BookingRepository;
import com.homeease.backend.repository.ServiceRepository;
import com.homeease.backend.repository.UserRepository;
import com.homeease.backend.repository.WorkerRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
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
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Doorstep PIN security against a REAL PostgreSQL (zonky embedded-postgres, throw-away, no credentials).
 *
 * <p>The Mockito suite ({@link BookingOtpServiceUnitTest}) mutates a plain in-memory {@code BookingOtp}, so it can
 * never notice what a real transaction does to a failed attempt: {@code verifyOtp} is {@code @Transactional} and
 * signals "wrong PIN" by throwing a RuntimeException, which rolls the transaction back, including the attempt
 * counter that was just saved. These tests read the COMMITTED row through an independent JDBC connection.
 */
@SpringBootTest(
        classes = BookingOtpPersistencePostgresTest.TestConfig.class,
        properties = {
                "spring.jpa.hibernate.ddl-auto=create",
                "spring.jpa.open-in-view=false",
                "homeease.booking.otp.max-attempts=5",
                "homeease.booking.otp.hmac-secret=unit-test-only-hmac-secret-not-a-real-credential"
        })
class BookingOtpPersistencePostgresTest {

    @Configuration
    @ImportAutoConfiguration({
            HibernateJpaAutoConfiguration.class,
            JpaRepositoriesAutoConfiguration.class,
            TransactionAutoConfiguration.class,
            JacksonAutoConfiguration.class})
    @EntityScan("com.homeease.backend.model.entity")
    @EnableJpaRepositories("com.homeease.backend.repository")
    @Import(BookingOtpService.class)
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

    @MockBean FcmNotificationService fcmNotificationService;
    @MockBean SimpMessagingTemplate messagingTemplate;
    @MockBean RedisTemplate<String, String> redisTemplate;

    @Autowired BookingOtpService otpService;
    @Autowired BookingOtpRepository otpRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired UserRepository userRepository;
    @Autowired WorkerRepository workerRepository;
    @Autowired ServiceRepository serviceRepository;
    @Autowired DataSource dataSource;
    @Autowired EntityManagerFactory entityManagerFactory;

    static final AtomicLong PHONE_SEQ = new AtomicLong(7_100_000_000L);

    JdbcTemplate jdbc;
    Booking booking;
    UUID workerUserId;

    @BeforeEach
    void freshFixtures() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DO $$ DECLARE r RECORD; BEGIN "
                + "FOR r IN SELECT tablename FROM pg_tables WHERE schemaname = 'public' LOOP "
                + "EXECUTE 'TRUNCATE TABLE ' || quote_ident(r.tablename) || ' CASCADE'; END LOOP; END $$");

        User customer = userRepository.save(User.builder()
                .fullName("PIN test customer").phoneNumber("+91" + PHONE_SEQ.incrementAndGet())
                .email("c" + PHONE_SEQ.get() + "@it.invalid").role(UserRole.CUSTOMER).build());
        User workerUser = userRepository.save(User.builder()
                .fullName("PIN test worker").phoneNumber("+91" + PHONE_SEQ.incrementAndGet())
                .email("w" + PHONE_SEQ.get() + "@it.invalid").role(UserRole.WORKER).build());
        Worker worker = workerRepository.save(Worker.builder()
                .user(workerUser).kycStatus("APPROVED").isOnline(true).isVerified(true).build());
        ServiceEntity service = serviceRepository.save(ServiceEntity.builder()
                .name("Electrician (fixture)").imageUrl("https://example.invalid/e.png").isActive(true).build());

        booking = bookingRepository.save(Booking.builder()
                .user(customer).worker(worker).service(service)
                .bookingType(BookingType.INSTANT).status(BookingStage.ARRIVED)
                .pinCode("0000").scheduledAt(Instant.now()).arrivedAt(Instant.now())
                .userLat(17.4486).userLng(78.3908)
                .baseAmount(new BigDecimal("199.00")).totalAmount(new BigDecimal("199.00"))
                .paymentMethod(PaymentMode.COD).createdAt(Instant.now())
                .build());
        workerUserId = workerUser.getUserId();
        inOpenSession(() -> otpService.issueOtp(loadBooking()));
    }

    /** Production controllers load the booking while the request-scoped session is open (OSIV); mimic that, with NO outer transaction. */
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

    private Booking loadBooking() {
        return bookingRepository.findById(booking.getBookingId()).orElseThrow();
    }

    private void verify(String pin) {
        inOpenSession(() -> otpService.verifyOtp(loadBooking(), workerUserId, pin));
    }

    private String wrongPin() {
        // any 4-digit value that is not the real one: derive from the stored hash so the test never guesses
        for (int i = 0; i < 10_000; i++) {
            String candidate = String.format("%04d", i);
            String hash = otpService.hashOtp(candidate, booking.getBookingId());
            String stored = jdbc.queryForObject(
                    "SELECT hashed_otp FROM booking_otp WHERE booking_id = ? AND status = 'ACTIVE'",
                    String.class, booking.getBookingId());
            if (!hash.equals(stored)) {
                return candidate;
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private int committedAttempts() {
        return jdbc.queryForObject("SELECT COALESCE(MAX(attempts), 0) FROM booking_otp WHERE booking_id = ?",
                Integer.class, booking.getBookingId());
    }

    private long committedStatusCount(String status) {
        return jdbc.queryForObject("SELECT count(*) FROM booking_otp WHERE booking_id = ? AND status = ?",
                Long.class, booking.getBookingId(), status);
    }

    @Test
    @DisplayName("a wrong PIN is counted: the attempt counter survives the exception (committed, not rolled back)")
    void wrongPin_attemptIsPersisted() {
        String wrong = wrongPin();

        assertThatThrownBy(() -> verify(wrong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Incorrect PIN");

        assertThat(committedAttempts())
                .as("attempts committed in booking_otp after ONE wrong PIN")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("brute force is stopped: after max-attempts wrong PINs the OTP is locked, even for the right PIN")
    void bruteForce_locksTheOtp_andTheCorrectPinIsThenRejected() {
        String wrong = wrongPin();
        String correct = null;
        for (int i = 0; i < 10_000 && correct == null; i++) {
            String candidate = String.format("%04d", i);
            if (otpService.hashOtp(candidate, booking.getBookingId()).equals(jdbc.queryForObject(
                    "SELECT hashed_otp FROM booking_otp WHERE booking_id = ? AND status = 'ACTIVE'",
                    String.class, booking.getBookingId()))) {
                correct = candidate;
            }
        }
        assertThat(correct).isNotNull();
        final String realPin = correct;

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> verify(wrong))
                    .isInstanceOf(RuntimeException.class);
        }

        assertThat(committedAttempts()).as("5 wrong PINs are all counted").isEqualTo(5);
        assertThat(committedStatusCount("ACTIVE")).as("no ACTIVE otp is left to guess against").isZero();

        // the attacker now has the right PIN (e.g. the 5001st guess) - it must NOT be accepted on a locked OTP
        assertThatThrownBy(() -> verify(realPin))
                .isInstanceOf(IllegalStateException.class);
        assertThat(committedStatusCount("USED")).as("a locked OTP is never consumed").isZero();
    }

    @Test
    @DisplayName("the lockout cannot be bypassed by asking for a fresh PIN right away")
    void lockout_blocksReissueForTheCooldownWindow() {
        String wrong = wrongPin();
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> verify(wrong))
                    .isInstanceOf(RuntimeException.class);
        }
        // customer screen calls GET /service-pin; with no ACTIVE otp it re-issues. After a brute-force lockout it must not.
        inOpenSession(() -> otpService.issueOtp(loadBooking()));

        assertThat(committedStatusCount("ACTIVE"))
                .as("a new ACTIVE otp (fresh 5 guesses) must not appear immediately after a brute-force lockout")
                .isZero();
    }
}
