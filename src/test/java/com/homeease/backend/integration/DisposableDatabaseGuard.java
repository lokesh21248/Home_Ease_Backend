package com.homeease.backend.integration;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Refuses to let an integration test use anything but a disposable LOCAL PostgreSQL database.
 *
 * <p>Integration tests such as {@code BookingKafkaIntegrationTest} insert and delete users, services and bookings and
 * run Flyway migrations. Pointing them at a real database (the committed defaults used to point at Supabase) would
 * write test data into it. A database is accepted only if ALL of these hold:
 * <ul>
 *   <li>the JDBC URL is {@code jdbc:postgresql://...};</li>
 *   <li>the host is localhost / 127.0.0.1 / ::1 (never a remote or managed host such as Supabase);</li>
 *   <li>the database name contains {@code test}, {@code disposable} or {@code _it} and is not {@code postgres}.</li>
 * </ul>
 * Error messages never include the URL's query string (it may carry a password).
 */
public final class DisposableDatabaseGuard {

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");
    private static final Pattern DISPOSABLE_NAME = Pattern.compile(".*(test|disposable|_it|-it).*");

    private DisposableDatabaseGuard() { }

    public static void requireSafe(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.toLowerCase(Locale.ROOT).startsWith("jdbc:postgresql://")) {
            throw refuse("the URL must start with jdbc:postgresql://");
        }
        final URI uri;
        try {
            uri = URI.create(jdbcUrl.substring("jdbc:".length()));
        } catch (IllegalArgumentException e) {
            throw refuse("the URL could not be parsed");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.contains("supabase") || host.contains("pooler")) {
            throw refuse("the host looks like a managed/production database");
        }
        if (!LOCAL_HOSTS.contains(host)) {
            throw refuse("the host must be localhost, 127.0.0.1 or ::1 (a disposable LOCAL database)");
        }
        String path = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "").toLowerCase(Locale.ROOT);
        if (path.isEmpty() || path.equals("postgres") || !DISPOSABLE_NAME.matcher(path).matches()) {
            throw refuse("the database name must contain 'test', 'disposable' or '_it' and must not be 'postgres'");
        }
    }

    private static IllegalStateException refuse(String why) {
        return new IllegalStateException("Refusing to run integration tests against this database: " + why
                + ". Create a disposable local PostgreSQL+PostGIS database (e.g. homeease_it_test) and pass its "
                + "JDBC URL in TEST_POSTGIS_JDBC_URL.");
    }
}
