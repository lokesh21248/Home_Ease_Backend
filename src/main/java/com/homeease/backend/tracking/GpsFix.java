package com.homeease.backend.tracking;

/**
 * One validated GPS observation of a service partner.
 *
 * @param timestampMs  device fix time (epoch millis); used for ordering and replay detection only, never compared
 *                     against another phone's clock
 * @param accuracyM    horizontal accuracy radius in metres, {@code null} when the device did not report one
 * @param speedMps     ground speed in metres per second, {@code null} when unknown
 * @param headingDeg   course over ground in [0,360), {@code null} when unknown
 */
public record GpsFix(double lat, double lng, long timestampMs, Double accuracyM, Double speedMps, Double headingDeg) {
}
