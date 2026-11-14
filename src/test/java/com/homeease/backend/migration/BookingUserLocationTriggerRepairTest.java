package com.homeease.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V3 installs a trigger that assigns {@code NEW.user_location}; that column does not exist in a database built from the
 * {@code postgresql/} migration chain, so there every INSERT/UPDATE on bookings failed ("record "new" has no field
 * "user_location""), which is what BookingKafkaIntegrationTest hit on CI. V26 repairs it.
 *
 * <p>These tests run the REAL migration files against a real, throw-away PostgreSQL (zonky embedded-postgres: temp
 * directory, random local port, no existing database is touched). PostGIS is not needed: only the trigger mechanics
 * are exercised, on a reduced bookings table. The full chain against PostGIS is BookingKafkaIntegrationTest.
 */
class BookingUserLocationTriggerRepairTest {

    private static final String V3 = "V3__alter_bookings_user_location.sql";
    private static final String V26 = "V26__repair_booking_user_location_trigger.sql";

    /** bookings as the postgresql/ chain builds it: there is NO user_location column. */
    private static final String BOOKINGS_FROM_THIS_CHAIN =
            "CREATE TABLE bookings (booking_id int PRIMARY KEY, user_lat double precision NOT NULL, "
                    + "user_lng double precision NOT NULL, status text NOT NULL DEFAULT 'SEARCHING')";

    /** bookings as the legacy root schema built it: user_location exists and is NOT NULL (V3 relaxes that). */
    private static final String BOOKINGS_FROM_LEGACY_SCHEMA =
            "CREATE TABLE bookings (booking_id int PRIMARY KEY, user_lat double precision NOT NULL, "
                    + "user_lng double precision NOT NULL, status text NOT NULL DEFAULT 'SEARCHING', "
                    + "user_location point NOT NULL)";

    private static final String INSERT = "INSERT INTO bookings (booking_id, user_lat, user_lng) VALUES (1, 17.385, 78.4867)";
    private static final String TRIGGERS = "SELECT count(*) FROM pg_trigger WHERE tgname = 'trg_booking_user_location'";
    private static final String FUNCTIONS = "SELECT count(*) FROM pg_proc WHERE proname = 'set_booking_user_location'";

    private static EmbeddedPostgres pg;
    private static DataSource ds;

    @BeforeAll
    static void startPostgres() throws Exception {
        pg = EmbeddedPostgres.start();
        ds = pg.getPostgresDatabase();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (pg != null) {
            pg.close();
        }
    }

    @BeforeEach
    void cleanSlate() throws Exception {
        run("DROP TABLE IF EXISTS bookings CASCADE; DROP FUNCTION IF EXISTS set_booking_user_location() CASCADE");
    }

    @Test
    void V3_alone_breaks_every_booking_write_on_a_database_built_from_this_chain() throws Exception {
        run(BOOKINGS_FROM_THIS_CHAIN);
        run(migration(V3));

        assertThatThrownBy(() -> run(INSERT))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("has no field \"user_location\"");
    }

    @Test
    void V26_repairs_it_so_booking_inserts_and_updates_work() throws Exception {
        run(BOOKINGS_FROM_THIS_CHAIN);
        run(migration(V3));
        run(migration(V26));

        run(INSERT);
        run("UPDATE bookings SET status = 'ACCEPTED' WHERE booking_id = 1");
        assertThat(count("SELECT count(*) FROM bookings WHERE status = 'ACCEPTED'")).isEqualTo(1);
        assertThat(count(TRIGGERS)).as("the broken trigger is gone").isZero();
        assertThat(count(FUNCTIONS)).as("and so is its function").isZero();
    }

    @Test
    void V26_is_safe_to_run_again() throws Exception {
        run(BOOKINGS_FROM_THIS_CHAIN);
        run(migration(V3));
        run(migration(V26));
        run(migration(V26));

        run(INSERT);
    }

    @Test
    void V26_changes_nothing_where_the_column_exists_so_a_legacy_database_keeps_working_as_before() throws Exception {
        run(BOOKINGS_FROM_LEGACY_SCHEMA);
        run(migration(V3));
        run(migration(V26));

        assertThat(count(TRIGGERS)).as("trigger kept").isEqualTo(1);
        assertThat(count(FUNCTIONS)).as("function kept").isEqualTo(1);
        run(INSERT);
        assertThat(count("SELECT count(*) FROM bookings WHERE user_location IS NOT NULL"))
                .as("the trigger still fills the column, exactly as before V26").isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private static void run(String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static long count(String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** The real migration file as Flyway would read it from the classpath. */
    private static String migration(String file) throws Exception {
        try (InputStream in = BookingUserLocationTriggerRepairTest.class
                .getResourceAsStream("/db/migration/postgresql/" + file)) {
            assertThat(in).as("migration " + file + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
