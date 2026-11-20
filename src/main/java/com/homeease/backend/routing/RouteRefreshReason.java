package com.homeease.backend.routing;

/**
 * Why the client is asking for a route. Lets the server apply the right throttle: a worker who has left the
 * suggested road legitimately needs a fresh route sooner than a periodic ETA refresh.
 */
public enum RouteRefreshReason {
    /** First route after acceptance, app reopen or reconnect. */
    INITIAL,
    /** Routine ETA refresh while the worker is moving along the route. */
    PERIODIC,
    /** The client detected the worker is no longer on the suggested route. */
    OFF_ROUTE;

    /** Lenient parse: unknown or missing values fall back to {@link #PERIODIC}. */
    public static RouteRefreshReason parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return PERIODIC;
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return PERIODIC;
        }
    }
}
