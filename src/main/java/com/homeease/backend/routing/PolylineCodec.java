package com.homeease.backend.routing;

import java.util.ArrayList;
import java.util.List;

/**
 * Google Encoded Polyline Algorithm (precision 1e5) codec.
 *
 * <p>The server decodes every provider polyline before returning it so a malformed or truncated string can
 * never reach a mobile client, and the encoder lets tests build deterministic fixtures. Points are always
 * {@code [latitude, longitude]}.</p>
 */
public final class PolylineCodec {

    private static final double PRECISION = 1e5;

    private PolylineCodec() {
    }

    /**
     * @throws IllegalArgumentException if the string is truncated/malformed or yields an out-of-range coordinate
     */
    public static List<double[]> decode(String encoded) {
        List<double[]> points = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) {
            return points;
        }
        int index = 0;
        int lat = 0;
        int lng = 0;
        final int len = encoded.length();
        while (index < len) {
            int[] latDelta = readValue(encoded, index);
            index = latDelta[1];
            if (index >= len) {
                throw new IllegalArgumentException("Truncated polyline: latitude without longitude");
            }
            int[] lngDelta = readValue(encoded, index);
            index = lngDelta[1];
            lat += latDelta[0];
            lng += lngDelta[0];
            double latitude = lat / PRECISION;
            double longitude = lng / PRECISION;
            if (latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0) {
                throw new IllegalArgumentException("Polyline coordinate out of range: " + latitude + "," + longitude);
            }
            points.add(new double[]{latitude, longitude});
        }
        return points;
    }

    /** Reads one zig-zag varint; returns {value, nextIndex}. */
    private static int[] readValue(String encoded, int start) {
        int index = start;
        int result = 0;
        int shift = 0;
        int b;
        do {
            if (index >= encoded.length()) {
                throw new IllegalArgumentException("Truncated polyline at index " + index);
            }
            b = encoded.charAt(index++) - 63;
            if (b < 0 || b > 63) {
                throw new IllegalArgumentException("Invalid polyline character at index " + (index - 1));
            }
            result |= (b & 0x1f) << shift;
            shift += 5;
            if (shift > 35) {
                throw new IllegalArgumentException("Polyline varint overflow at index " + index);
            }
        } while (b >= 0x20);
        int value = ((result & 1) != 0) ? ~(result >> 1) : (result >> 1);
        return new int[]{value, index};
    }

    public static String encode(List<double[]> points) {
        StringBuilder sb = new StringBuilder();
        long prevLat = 0;
        long prevLng = 0;
        for (double[] p : points) {
            long lat = Math.round(p[0] * PRECISION);
            long lng = Math.round(p[1] * PRECISION);
            writeValue(sb, lat - prevLat);
            writeValue(sb, lng - prevLng);
            prevLat = lat;
            prevLng = lng;
        }
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, long delta) {
        long v = delta < 0 ? ~(delta << 1) : (delta << 1);
        while (v >= 0x20) {
            sb.append((char) ((0x20 | (v & 0x1f)) + 63));
            v >>= 5;
        }
        sb.append((char) (v + 63));
    }
}
