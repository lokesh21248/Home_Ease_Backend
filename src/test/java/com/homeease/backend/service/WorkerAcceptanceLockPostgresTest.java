package com.homeease.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.homeease.backend.exception.WorkerBusyException;
import com.homeease.backend.model.entity.Booking;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.BookingType;
import com.homeease.backend.repository.BookingRepository;
import com.homeease.backend.repository.WorkerRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * The worker-acceptance lock against a REAL PostgreSQL (zonky embedded-postgres: real binaries, throw-away temp
 * directory, random local port; no existing database is touched).
 *
 * <p>Layer 1 runs the exact SQL text of {@code WorkerRepository.lockWorkerAcceptance} (read from its @Query).
 * Layer 2 drives the real {@link WorkerAcceptanceGuard} with two repository proxies that execute the lock and the
 * busy-check as JDBC on the calling thread's transaction, so the guard's own ordering logic is what is being tested
 * under real lock semantics (READ COMMITTED, the Postgres default). The JPA mapping of the repositories is not
 * exercised (it needs PostGIS and the Flyway schema); only the lock protocol and the SQL text are.
 */
class WorkerAcceptanceLockPostgresTest {

    private static EmbeddedPostgres pg;
    private static DataSource ds;

    private static String lockSql;

    @BeforeAll
    static void startPostgres() throws Exception {
        pg = EmbeddedPostgres.start();
        ds = pg.getPostgresDatabase();
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE sim_booking (booking_id uuid PRIMARY KEY, worker_id uuid, status text NOT NULL)");
        }
        String raw = WorkerRepository.class.getMethod("lockWorkerAcceptance", String.class)
                .getAnnotation(Query.class).value();
        lockSql = raw.replace(":lockKey", "?");
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (pg != null) {
            pg.close();
        }
    }

    @BeforeEach
    void clean() throws Exception {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM sim_booking");
        }
    }

    // ------------------------------------------------------------------------------------ layer 1: the SQL itself

    @Test
    void the_production_query_text_is_valid_on_real_postgres_inside_a_transaction() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(lockSql)) {
                ps.setString(1, "worker_accept:" + UUID.randomUUID());
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("the CAST(void AS text) trick yields one row").isTrue();
                    assertThat(rs.getString(1)).isEmpty();
                }
            }
            c.commit();
        }
    }

    @Test
    void the_lock_is_held_until_commit_and_released_by_rollback_too() throws Exception {
        String key = "worker_accept:" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection holder = ds.getConnection()) {
            holder.setAutoCommit(false);
            lock(holder, key);

            CountDownLatch acquired = new CountDownLatch(1);
            Future<Long> waiter = pool.submit(() -> {
                try (Connection c = ds.getConnection()) {
                    c.setAutoCommit(false);
                    long t0 = System.nanoTime();
                    lock(c, key);                       // must block until the holder ends its transaction
                    acquired.countDown();
                    c.rollback();
                    return (System.nanoTime() - t0) / 1_000_000;
                }
            });

            assertThat(acquired.await(600, TimeUnit.MILLISECONDS)).as("second tx is blocked while the first is open").isFalse();
            holder.rollback();                          // rollback releases a transaction-scoped advisory lock
            long waitedMs = waiter.get(5, TimeUnit.SECONDS);
            System.out.println("[pg-lock] second transaction waited " + waitedMs + " ms, then proceeded after rollback");
            assertThat(waitedMs).isGreaterThanOrEqualTo(500);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void locks_for_different_workers_do_not_block_each_other() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection a = ds.getConnection()) {
            a.setAutoCommit(false);
            lock(a, "worker_accept:" + UUID.randomUUID());
            Future<Long> other = pool.submit(() -> {
                try (Connection b = ds.getConnection()) {
                    b.setAutoCommit(false);
                    long t0 = System.nanoTime();
                    lock(b, "worker_accept:" + UUID.randomUUID());
                    b.rollback();
                    return (System.nanoTime() - t0) / 1_000_000;
                }
            });
            assertThat(other.get(3, TimeUnit.SECONDS)).as("a different worker's lock is free immediately").isLessThan(1500);
            a.rollback();
        } finally {
            pool.shutdownNow();
        }
    }

    private static void lock(Connection c, String key) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(lockSql)) {
            ps.setString(1, key);
            ps.executeQuery().close();
        }
    }

    // ---------------------------------------------------- layer 2: the real WorkerAcceptanceGuard under real locks

    /** One database transaction per thread, as a real @Transactional accept method would have. */
    private static final ThreadLocal<Connection> TX = new ThreadLocal<>();

    private static WorkerRepository workerRepositoryOverJdbc() {
        return (WorkerRepository) Proxy.newProxyInstance(WorkerRepository.class.getClassLoader(),
                new Class<?>[] {WorkerRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("lockWorkerAcceptance")) {
                        lock(TX.get(), (String) args[0]);
                        return "";
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static BookingRepository bookingRepositoryOverJdbc() {
        return (BookingRepository) Proxy.newProxyInstance(BookingRepository.class.getClassLoader(),
                new Class<?>[] {BookingRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("findByWorker_WorkerIdAndStatusInOrderByScheduledAtAsc")) {
                        UUID workerId = (UUID) args[0];
                        Collection<?> stages = (Collection<?>) args[1];
                        List<Booking> out = new ArrayList<>();
                        String in = String.join(",", stages.stream().map(s -> "'" + s + "'").toList());
                        try (PreparedStatement ps = TX.get().prepareStatement(
                                "SELECT booking_id, status FROM sim_booking WHERE worker_id = ? AND status IN (" + in + ")")) {
                            ps.setObject(1, workerId);
                            try (ResultSet rs = ps.executeQuery()) {
                                while (rs.next()) {
                                    out.add(Booking.builder().bookingId((UUID) rs.getObject(1))
                                            .status(BookingStage.valueOf(rs.getString(2))).build());
                                }
                            }
                        }
                        return out;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /**
     * Runs {@code attempts} concurrent "accept" transactions for ONE worker, each for its own booking. The sleep
     * between the busy-check and the write widens the race window so an unprotected implementation reliably loses.
     */
    private int concurrentAccepts(int attempts, boolean guardEnabled) throws Exception {
        WorkerAcceptanceGuard guard = new WorkerAcceptanceGuard(workerRepositoryOverJdbc(), bookingRepositoryOverJdbc(), guardEnabled);
        UUID workerId = UUID.randomUUID();
        AtomicInteger wins = new AtomicInteger();
        AtomicInteger busy = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            futures.add(pool.submit(() -> {
                Booking mine = Booking.builder().bookingId(UUID.randomUUID()).bookingType(BookingType.INSTANT)
                        .status(BookingStage.SEARCHING).build();
                try (Connection c = ds.getConnection()) {
                    c.setAutoCommit(false);                    // one transaction, READ COMMITTED
                    TX.set(c);
                    start.await();
                    try {
                        guard.ensureWorkerFree(workerId, mine);   // lock + busy check (the code under test)
                        Thread.sleep(150);                        // widen the check-then-write window
                        try (PreparedStatement ps = c.prepareStatement(
                                "INSERT INTO sim_booking(booking_id, worker_id, status) VALUES (?, ?, 'ACCEPTED')")) {
                            ps.setObject(1, mine.getBookingId());
                            ps.setObject(2, workerId);
                            ps.executeUpdate();
                        }
                        c.commit();                              // releases the advisory lock
                        wins.incrementAndGet();
                    } catch (WorkerBusyException e) {
                        c.rollback();
                        busy.incrementAndGet();
                    }
                } finally {
                    TX.remove();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT count(*) FROM sim_booking WHERE worker_id = ?")) {
            ps.setObject(1, workerId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                int rows = rs.getInt(1);
                System.out.println("[pg-lock] guard " + (guardEnabled ? "ON " : "OFF") + ": attempts=" + attempts
                        + " wins=" + wins.get() + " busy=" + busy.get() + " committedRows=" + rows);
                assertThat(rows).as("committed in-flight rows must equal the number of winners").isEqualTo(wins.get());
            }
        }
        return wins.get();
    }

    @Test
    void CONTROL_without_the_guard_several_accepts_for_one_worker_all_win() throws Exception {
        assertThat(concurrentAccepts(5, false)).as("proves the harness can see the race").isGreaterThan(1);
    }

    @Test
    void with_the_guard_exactly_one_of_many_concurrent_accepts_wins_on_real_postgres() throws Exception {
        assertThat(concurrentAccepts(5, true)).isEqualTo(1);
    }

    @Test
    void with_the_guard_the_result_is_stable_over_repeated_races() throws Exception {
        for (int round = 0; round < 3; round++) {
            assertThat(concurrentAccepts(8, true)).as("round " + round).isEqualTo(1);
        }
    }

    @Test
    void a_worker_who_finished_the_first_job_may_accept_again() throws Exception {
        WorkerAcceptanceGuard guard = new WorkerAcceptanceGuard(workerRepositoryOverJdbc(), bookingRepositoryOverJdbc(), true);
        UUID workerId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            TX.set(c);
            Booking target = Booking.builder().bookingId(UUID.randomUUID()).bookingType(BookingType.INSTANT).build();
            guard.ensureWorkerFree(workerId, target);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO sim_booking(booking_id, worker_id, status) VALUES (?, ?, 'COMPLETED')")) {
                ps.setObject(1, first);
                ps.setObject(2, workerId);
                ps.executeUpdate();
            }
            c.commit();
            // COMPLETED is not an in-flight stage, so the next acceptance is allowed
            guard.ensureWorkerFree(workerId, Booking.builder().bookingId(UUID.randomUUID())
                    .bookingType(BookingType.INSTANT).build());
            c.rollback();
        } finally {
            TX.remove();
        }
    }
}
