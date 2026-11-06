package com.homeease.backend.service;

import com.homeease.backend.repository.AssignmentWeightConfigRepository;
import com.homeease.backend.repository.ServiceSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
public class FraudDetectionService {

    private static final Logger logger = LoggerFactory.getLogger(FraudDetectionService.class);

    private static final double DEFAULT_MAX_SPEED_KMH = 150.0;
    private static final double MAX_ALLOWABLE_ACCURACY_METERS = 100.0;
    private static final int MAX_BOOKINGS_PER_WINDOW = 5;
    private static final Duration VELOCITY_WINDOW = Duration.ofMinutes(10);

    private final RedisTemplate<String, String> redisTemplate;
    private final ServiceSessionRepository sessionRepository;
    private final AssignmentWeightConfigRepository weightConfigRepository;

    public FraudDetectionService(RedisTemplate<String, String> redisTemplate,
                                 ServiceSessionRepository sessionRepository,
                                 AssignmentWeightConfigRepository weightConfigRepository) {
        this.redisTemplate = redisTemplate;
        this.sessionRepository = sessionRepository;
        this.weightConfigRepository = weightConfigRepository;
    }

    public static class TelemetryValidationResult {
        private final boolean valid;
        private final String reason;
        private final double calculatedSpeedKmh;

        public TelemetryValidationResult(boolean valid, String reason, double calculatedSpeedKmh) {
            this.valid = valid;
            this.reason = reason;
            this.calculatedSpeedKmh = calculatedSpeedKmh;
        }

        public boolean isValid() { return valid; }
        public String getReason() { return reason; }
        public double getCalculatedSpeedKmh() { return calculatedSpeedKmh; }
    }

    /**
     * Validates incoming GPS telemetry against teleportation/GPS spoofing and accuracy bounds.
     */
    public TelemetryValidationResult validateWorkerLocation(UUID workerId, double lat, double lng,
                                                            double accuracyMeters, long timestampMs,
                                                            String zonePincode) {
        // 1. Accuracy Check
        if (accuracyMeters > MAX_ALLOWABLE_ACCURACY_METERS) {
            logger.warn("Worker {} telemetry rejected: accuracy {}m exceeds threshold {}m",
                    workerId, accuracyMeters, MAX_ALLOWABLE_ACCURACY_METERS);
            return new TelemetryValidationResult(false, "EXCESSIVE_INACCURACY", 0.0);
        }

        // 2. Fetch previous ping from Redis
        String key = "worker:last_geo:" + workerId;
        String lastData = redisTemplate.opsForValue().get(key);

        double maxSpeedKmh = weightConfigRepository.findBestMatchingConfig(null, zonePincode)
                .map(c -> c.getMaxSpeedKmhThreshold() != null ? c.getMaxSpeedKmhThreshold() : DEFAULT_MAX_SPEED_KMH)
                .orElse(DEFAULT_MAX_SPEED_KMH);

        double calculatedSpeed = 0.0;

        if (lastData != null) {
            try {
                String[] parts = lastData.split(":");
                double prevLat = Double.parseDouble(parts[0]);
                double prevLng = Double.parseDouble(parts[1]);
                long prevTimeMs = Long.parseLong(parts[2]);

                long timeDeltaSeconds = (timestampMs - prevTimeMs) / 1000;
                if (timeDeltaSeconds > 0) {
                    double distMeters = haversineDistance(prevLat, prevLng, lat, lng);
                    double speedMps = distMeters / timeDeltaSeconds;
                    calculatedSpeed = speedMps * 3.6; // convert m/s to km/h

                    if (calculatedSpeed > maxSpeedKmh) {
                        logger.warn("FRAUD ALERT: Worker {} suspected GPS spoofing/teleportation. Speed: {} km/h (Max: {} km/h, Distance: {}m, Time: {}s)",
                                workerId, Math.round(calculatedSpeed), maxSpeedKmh, Math.round(distMeters), timeDeltaSeconds);
                        return new TelemetryValidationResult(false, "IMPOSSIBLE_VELOCITY", calculatedSpeed);
                    }
                }
            } catch (Exception e) {
                logger.error("Error evaluating previous telemetry data for worker {}: {}", workerId, e.getMessage());
            }
        }

        // Cache valid ping for next comparison (TTL 10 minutes)
        String currentData = lat + ":" + lng + ":" + timestampMs;
        redisTemplate.opsForValue().set(key, currentData, Duration.ofMinutes(10));

        return new TelemetryValidationResult(true, "OK", calculatedSpeed);
    }

    /**
     * Verifies that job proof exists before settlement or payout is processed.
     */
    public boolean verifyJobCompletionProof(UUID bookingId) {
        String proofKey = "booking:proof:" + bookingId;
        String recorded = redisTemplate.opsForValue().get(proofKey);
        if (recorded != null && !recorded.isBlank()) {
            return true;
        }
        return sessionRepository.findByBooking_BookingId(bookingId).isPresent();
    }

    /**
     * Protects capacity from malicious booking velocity attacks.
     */
    public boolean checkBookingVelocity(UUID userId) {
        String key = "rate:booking_velocity:" + userId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, VELOCITY_WINDOW);
        }
        if (count != null && count > MAX_BOOKINGS_PER_WINDOW) {
            logger.warn("SECURITY ALERT: User {} exceeded booking rate limit ({} requests in window)",
                    userId, count);
            return false;
        }
        return true;
    }

    private double haversineDistance(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371000.0; // Earth radius in meters
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double deltaPhi = Math.toRadians(lat2 - lat1);
        double deltaLambda = Math.toRadians(lon2 - lon1);

        double a = Math.sin(deltaPhi / 2.0) * Math.sin(deltaPhi / 2.0) +
                   Math.cos(phi1) * Math.cos(phi2) *
                   Math.sin(deltaLambda / 2.0) * Math.sin(deltaLambda / 2.0);
        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
        return R * c;
    }
}
