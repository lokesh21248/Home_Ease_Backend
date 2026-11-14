package com.homeease.backend.service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Short-lived result cache for the public nearby-workers preview, so repeated calls from the same area do not each
 * run the discovery path (about 12 Redis commands plus database queries).
 *
 * <p>Key: service, sub-service, radius, and the coordinates rounded to {@code coordinateDecimals} (4 = about 11 m).
 * Callers inside the same cell share one result, so a returned distance can differ from the exact one by at most
 * about one cell width; ETAs are in whole minutes so this is not visible. Entries live {@code ttlSeconds} (default
 * 10 s, shorter than a worker's own ping interval), so availability is never staler than that. 0 disables caching.
 */
final class NearbyWorkersCache<T> {

    private record Key(UUID serviceId, UUID subServiceId, long radiusMeters, long latCell, long lngCell) { }

    private record Entry<T>(List<T> value, long expiresAtMs) { }

    private final long ttlMs;
    private final int maxEntries;
    private final double cellScale;
    private final LongSupplier clockMs;
    private final ConcurrentHashMap<Key, Entry<T>> entries = new ConcurrentHashMap<>();

    NearbyWorkersCache(int ttlSeconds, int coordinateDecimals, int maxEntries, LongSupplier clockMs) {
        this.ttlMs = Math.max(0, ttlSeconds) * 1000L;
        this.cellScale = Math.pow(10, Math.max(0, coordinateDecimals));
        this.maxEntries = maxEntries;
        this.clockMs = clockMs;
    }

    boolean isEnabled() {
        return ttlMs > 0;
    }

    List<T> get(UUID serviceId, UUID subServiceId, double lat, double lng, double radiusMeters, Supplier<List<T>> loader) {
        if (!isEnabled()) {
            return loader.get();
        }
        long now = clockMs.getAsLong();
        Key key = new Key(serviceId, subServiceId, Math.round(radiusMeters),
                Math.round(lat * cellScale), Math.round(lng * cellScale));
        Entry<T> hit = entries.get(key);
        if (hit != null && hit.expiresAtMs() > now) {
            return hit.value();
        }
        List<T> loaded = loader.get();
        if (entries.size() >= maxEntries) {
            entries.entrySet().removeIf(e -> e.getValue().expiresAtMs() <= now);
            if (entries.size() >= maxEntries) {
                entries.clear(); // bounded memory beats a perfect hit rate
            }
        }
        entries.put(key, new Entry<>(List.copyOf(loaded), now + ttlMs));
        return loaded;
    }

    int size() {
        return entries.size();
    }
}
