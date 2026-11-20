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
                            Long staticDurationSeconds) {
}
