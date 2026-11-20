package com.homeease.backend.routing;

/** Small, dependency-free geo helpers used by routing validation and cache-reuse decisions. */
public final class GeoMath {

    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private GeoMath() {
    }

    public static boolean isValidCoordinate(Double lat, Double lng) {
        return lat != null && lng != null
                && Double.isFinite(lat) && Double.isFinite(lng)
                && lat >= -90.0 && lat <= 90.0
                && lng >= -180.0 && lng <= 180.0;
    }

    /**
     * A coordinate that is valid <em>and</em> not the (0,0) "null island" placeholder that appears when a
     * client or a default-initialised column sends no real location.
     */
    public static boolean isUsableCoordinate(Double lat, Double lng) {
        if (!isValidCoordinate(lat, lng)) {
            return false;
        }
        return Math.abs(lat) > 1e-6 || Math.abs(lng) > 1e-6;
    }

    public static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_METERS * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
