package com.homeease.tracking.util;

/**
 * High-performance, zero-dependency Geohash encoder and spatial indexer.
 * Uses standard Morton space-filling z-order curve with Base32 encoding.
 *
 * Cell dimensions at equatorial latitudes:
 *  - Precision 5: ~4.9 km x ~4.9 km (Neighborhood / Zone)
 *  - Precision 6: ~1.2 km x ~0.61 km (Sub-locality)
 *  - Precision 7: ~153 m x ~153 m (Street block - ideal for dispatch bucketing)
 *  - Precision 8: ~38 m x ~19 m (Parcel level)
 */
public final class GeohashUtils {

    private static final char[] BASE32 = {
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'b', 'c', 'd', 'e', 'f', 'g',
            'h', 'j', 'k', 'm', 'n', 'p', 'q', 'r',
            's', 't', 'u', 'v', 'w', 'x', 'y', 'z'
    };

    private static final int[] BITS = {16, 8, 4, 2, 1};

    private GeohashUtils() {
        // Utility class
    }

    /**
     * Encodes latitude and longitude coordinates into a standard Geohash string.
     *
     * @param lat Latitude in degrees [-90.0, 90.0]
     * @param lng Longitude in degrees [-180.0, 180.0]
     * @param precision Number of Base32 characters (1 to 12). Recommended: 7 (~150m)
     * @return Standard Base32 Geohash string
     */
    public static String encode(double lat, double lng, int precision) {
        if (precision < 1 || precision > 12) {
            precision = 7;
        }

        double[] latInterval = {-90.0, 90.0};
        double[] lngInterval = {-180.0, 180.0};

        StringBuilder geohash = new StringBuilder(precision);
        boolean isEven = true; // start with longitude
        int bit = 0;
        int ch = 0;

        while (geohash.length() < precision) {
            double mid;
            if (isEven) {
                mid = (lngInterval[0] + lngInterval[1]) / 2.0;
                if (lng >= mid) {
                    ch |= BITS[bit];
                    lngInterval[0] = mid;
                } else {
                    lngInterval[1] = mid;
                }
            } else {
                mid = (latInterval[0] + latInterval[1]) / 2.0;
                if (lat >= mid) {
                    ch |= BITS[bit];
                    latInterval[0] = mid;
                } else {
                    latInterval[1] = mid;
                }
            }

            isEven = !isEven;
            if (bit < 4) {
                bit++;
            } else {
                geohash.append(BASE32[ch]);
                bit = 0;
                ch = 0;
            }
        }

        return geohash.toString();
    }

    /**
     * Convenience method with default precision 7 (~153m street block).
     */
    public static String encode(double lat, double lng) {
        return encode(lat, lng, 7);
    }
}
