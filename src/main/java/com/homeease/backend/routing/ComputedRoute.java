package com.homeease.backend.routing;

/**
 * A road route as returned by the routing provider.
 *
 * @param encodedPolyline       validated Google encoded polyline (precision 1e5), at least two points
 * @param distanceMeters        provider route distance
 * @param durationSeconds       provider route duration (traffic-aware when the provider supplies it)
 * @param staticDurationSeconds provider duration without live traffic, or {@code null} if not supplied
 */
public record ComputedRoute(String encodedPolyline,
                            long distanceMeters,
                            long durationSeconds,
                            Long staticDurationSeconds,
                            java.util.List<RouteStep> steps) {

    public ComputedRoute(String encodedPolyline, long distanceMeters, long durationSeconds, Long staticDurationSeconds) {
        this(encodedPolyline, distanceMeters, durationSeconds, staticDurationSeconds, java.util.List.of());
    }

    public ComputedRoute {
        steps = steps == null ? java.util.List.of() : java.util.List.copyOf(steps);
    }

    /**
     * One provider manoeuvre (Google Routes {@code legs[].steps[]}): the real turn-by-turn instruction text, the
     * manoeuvre code (e.g. TURN_LEFT) and where it starts. Nothing is synthesised by the server.
     */
    public record RouteStep(String instruction, String maneuver, long distanceMeters,
                            double startLat, double startLng, double endLat, double endLng) {
    }
}
