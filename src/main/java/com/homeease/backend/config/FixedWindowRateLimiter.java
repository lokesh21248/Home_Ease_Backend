package com.homeease.backend.config;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Small in-memory fixed-window rate limiter keyed by an arbitrary string, with bounded memory.
 *
 * <p>Fixed windows allow a burst of up to 2x the limit across a window boundary; that is acceptable here because the
 * purpose is to cap abuse and cost, not to meter precisely. Per JVM only: with several API instances the effective
 * limit is multiplied by the instance count.
 */
public final class FixedWindowRateLimiter {

    /** Outcome of one acquire attempt. */
    public record Decision(boolean allowed, long retryAfterSeconds) { }

    private static final class Window {
        final long startMs;
        int count;

        Window(long startMs) {
            this.startMs = startMs;
        }
    }

    private final int limitPerWindow;
    private final long windowMs;
    private final int maxKeys;
    private final LongSupplier clockMs;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(int limitPerWindow, long windowMs, int maxKeys, LongSupplier clockMs) {
        this.limitPerWindow = limitPerWindow;
        this.windowMs = windowMs;
        this.maxKeys = maxKeys;
        this.clockMs = clockMs;
    }

    public FixedWindowRateLimiter(int limitPerWindow, long windowMs) {
        this(limitPerWindow, windowMs, 10_000, System::currentTimeMillis);
    }

    /** A limit of 0 or less means "unlimited" (the limiter is off). */
    public boolean isEnabled() {
        return limitPerWindow > 0;
    }

    public Decision tryAcquire(String key) {
        if (!isEnabled()) {
            return new Decision(true, 0);
        }
        long now = clockMs.getAsLong();
        if (windows.size() >= maxKeys) {
            prune(now);
        }
        Window w = windows.compute(key, (k, old) -> {
            if (old == null || now - old.startMs >= windowMs) {
                Window fresh = new Window(now);
                fresh.count = 1;
                return fresh;
            }
            old.count++;
            return old;
        });
        if (w.count <= limitPerWindow) {
            return new Decision(true, 0);
        }
        long retryMs = Math.max(0, w.startMs + windowMs - now);
        return new Decision(false, Math.max(1, (retryMs + 999) / 1000));
    }

    /** Removes expired windows; if the map is still at capacity, drops everything (fail open for memory safety). */
    private void prune(long now) {
        windows.entrySet().removeIf(e -> now - e.getValue().startMs >= windowMs);
        if (windows.size() >= maxKeys) {
            windows.clear();
        }
    }

    int trackedKeys() {
        return windows.size();
    }
}
