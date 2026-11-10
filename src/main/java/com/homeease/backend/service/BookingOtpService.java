package com.homeease.backend.service;

import com.homeease.backend.model.entity.Booking;
import com.homeease.backend.model.entity.BookingOtp;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.OtpStatus;
import com.homeease.backend.repository.BookingOtpRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Service
public class BookingOtpService {

    private static final Logger logger = LoggerFactory.getLogger(BookingOtpService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String REDIS_CUSTOMER_PIN_PREFIX = "booking:customer_pin:";

    private final BookingOtpRepository otpRepository;
    private final FcmNotificationService fcmNotificationService;
    private final SimpMessagingTemplate messagingTemplate;
    private final RedisTemplate<String, String> redisTemplate;

    @Value("${homeease.booking.otp.ttl-seconds:900}") // 15 mins
    private int otpTtlSeconds = 900;

    @Value("${homeease.booking.otp.max-attempts:5}")
    private int maxAttempts = 5;

    @Value("${homeease.booking.otp.resend-cooldown-seconds:60}")
    private int resendCooldownSeconds = 60;

    @Value("${homeease.booking.otp.hmac-secret:HomeEase-Secure-Doorstep-Pin-Secret-2026-X9A7}")
    private String hmacSecret = "HomeEase-Secure-Doorstep-Pin-Secret-2026-X9A7";

    @Autowired
    public BookingOtpService(BookingOtpRepository otpRepository,
                             FcmNotificationService fcmNotificationService,
                             SimpMessagingTemplate messagingTemplate,
                             @Autowired(required = false) RedisTemplate<String, String> redisTemplate) {
        this.otpRepository = otpRepository;
        this.fcmNotificationService = fcmNotificationService;
        this.messagingTemplate = messagingTemplate;
        this.redisTemplate = redisTemplate;
    }

    public BookingOtpService(BookingOtpRepository otpRepository,
                             FcmNotificationService fcmNotificationService,
                             SimpMessagingTemplate messagingTemplate) {
        this(otpRepository, fcmNotificationService, messagingTemplate, null);
    }

    /**
     * Issues a new cryptographically secure 4-digit PIN for the booking upon worker arrival.
     * Only the SHA-256 hash is persisted in the database (never plaintext).
     * Plaintext is stored in Redis cache with TTL solely for authenticated customer retrieval.
     * It is NEVER returned in worker responses, public WebSocket topics, or general logs.
     */
    @Transactional
    public BookingOtp issueOtp(Booking booking) {
        if (booking == null) {
            throw new IllegalArgumentException("Booking cannot be null");
        }

        Instant now = Instant.now();

        // Check if active OTP already exists and cooldown has not elapsed
        Optional<BookingOtp> existing = otpRepository.findFirstByBooking_BookingIdAndStatus(
                booking.getBookingId(), OtpStatus.ACTIVE);

        if (existing.isPresent()) {
            BookingOtp active = existing.get();
            if (active.getResendCooldownUntil() != null && active.getResendCooldownUntil().isAfter(now)) {
                logger.info("OTP resend cooldown active for booking {}", booking.getBookingId());
                return active;
            }
            // Expire previous active OTP before reissuing
            active.setStatus(OtpStatus.REVOKED);
            active.setUpdatedAt(now);
            otpRepository.save(active);
        }

        // Generate cryptographically secure 4-digit PIN (0000 - 9999)
        String rawOtp = String.format("%04d", SECURE_RANDOM.nextInt(10000));
        String hashedOtp = hashOtp(rawOtp, booking.getBookingId());

        String customerPhone = booking.getRecipientPhone() != null && !booking.getRecipientPhone().isBlank()
                ? booking.getRecipientPhone()
                : (booking.getUser() != null ? booking.getUser().getPhoneNumber() : "UNKNOWN");
        String phoneHash = hashValue(customerPhone);

        BookingOtp otpEntity = BookingOtp.builder()
                .booking(booking)
                .hashedOtp(hashedOtp)
                .phoneHash(phoneHash)
                .expiresAt(now.plusSeconds(otpTtlSeconds))
                .maxAttempts(maxAttempts)
                .attempts(0)
                .resendCooldownUntil(now.plusSeconds(resendCooldownSeconds))
                .status(OtpStatus.ACTIVE)
                .build();

        otpEntity = otpRepository.save(otpEntity);

        // Store plaintext in Redis exclusively for authenticated customer retrieval
        if (redisTemplate != null) {
            try {
                redisTemplate.opsForValue().set(
                        REDIS_CUSTOMER_PIN_PREFIX + booking.getBookingId(),
                        rawOtp,
                        Duration.ofSeconds(otpTtlSeconds)
                );
            } catch (Exception e) {
                logger.warn("Failed to cache customer PIN in Redis for booking {}: {}", booking.getBookingId(), e.getMessage());
            }
        }

        // Mask PIN on booking entity so database column never holds unhashed PIN
        booking.setPinCode("****");

        // Notify customer privately (FCM & user-authenticated topic)
        notifyCustomerOfArrival(booking, rawOtp);
        logger.info("Successfully issued secure 4-digit Service Start PIN for booking {}", booking.getBookingId());
        return otpEntity;
    }

    /**
     * Retrieve the plaintext PIN for the authenticated customer.
     * Returns null if expired, missing, or already verified.
     */
    public String getCustomerPin(Booking booking) {
        if (booking == null || redisTemplate == null) return null;
        try {
            return redisTemplate.opsForValue().get(REDIS_CUSTOMER_PIN_PREFIX + booking.getBookingId());
        } catch (Exception e) {
            logger.warn("Failed to get customer PIN from Redis for booking {}: {}", booking.getBookingId(), e.getMessage());
            return null;
        }
    }

    /**
     * Assigned worker submits the customer-provided 4-digit PIN.
     * Enforces ARRIVED booking status, assigned worker ownership, attempt limits, and hash verification.
     */
    @Transactional
    public boolean verifyOtp(Booking booking, UUID workerUserId, String submittedOtp) {
        if (booking == null || submittedOtp == null || !isValidOtpFormat(submittedOtp)) {
            throw new IllegalArgumentException("Invalid PIN format. Must be exactly 4 numeric digits.");
        }

        // 1. Status check: Booking MUST be in ARRIVED status
        if (booking.getStatus() != BookingStage.ARRIVED) {
            if (booking.getStatus() == BookingStage.IN_PROGRESS) {
                throw new IllegalStateException("Service has already started for this booking.");
            }
            if (booking.getStatus() == BookingStage.COMPLETED) {
                throw new IllegalStateException("Service is already completed for this booking.");
            }
            throw new IllegalStateException("Cannot start service when booking is in status: " + booking.getStatus() + ". Partner must mark ARRIVED first.");
        }

        // 2. Worker ownership check: only the assigned worker can verify PIN
        if (booking.getWorker() == null || booking.getWorker().getUser() == null ||
                !booking.getWorker().getUser().getUserId().equals(workerUserId)) {
            throw new SecurityException("Unauthorized: Only the assigned worker may verify PIN for this booking.");
        }

        Instant now = Instant.now();
        String trimmedOtp = submittedOtp.trim();

        Optional<BookingOtp> otpOpt = otpRepository.findFirstByBooking_BookingIdAndStatus(
                booking.getBookingId(), OtpStatus.ACTIVE);

        if (otpOpt.isEmpty()) {
            throw new IllegalStateException("No active Service Start PIN found for this booking. Please ask customer to refresh.");
        }

        BookingOtp otp = otpOpt.get();

        // 3. Check expiration
        if (otp.getExpiresAt().isBefore(now)) {
            otp.setStatus(OtpStatus.EXPIRED);
            otp.setUpdatedAt(now);
            otpRepository.save(otp);
            evictCustomerPin(booking.getBookingId());
            throw new IllegalStateException("Service Start PIN has expired. Please ask customer to refresh their screen.");
        }

        // 4. Check brute-force attempts
        if (otp.getAttempts() >= otp.getMaxAttempts()) {
            otp.setStatus(OtpStatus.REVOKED);
            otp.setUpdatedAt(now);
            otpRepository.save(otp);
            evictCustomerPin(booking.getBookingId());
            throw new IllegalStateException("Maximum verification attempts exceeded. PIN verification is locked.");
        }

        // 5. Compare constant-time hash
        String computedHash = hashOtp(trimmedOtp, booking.getBookingId());
        boolean matchesHash = MessageDigest.isEqual(
                computedHash.getBytes(StandardCharsets.UTF_8),
                otp.getHashedOtp().getBytes(StandardCharsets.UTF_8)
        );

        if (!matchesHash) {
            otp.setAttempts(otp.getAttempts() + 1);
            otp.setUpdatedAt(now);
            if (otp.getAttempts() >= otp.getMaxAttempts()) {
                otp.setStatus(OtpStatus.REVOKED);
                evictCustomerPin(booking.getBookingId());
            }
            otpRepository.save(otp);
            int remaining = Math.max(0, otp.getMaxAttempts() - otp.getAttempts());
            throw new IllegalArgumentException("Incorrect PIN. Attempts remaining: " + remaining);
        }

        // 6. Verified successfully: mark OTP as USED and evict cached plaintext PIN
        otp.setStatus(OtpStatus.USED);
        otp.setUpdatedAt(now);
        otpRepository.save(otp);
        evictCustomerPin(booking.getBookingId());

        logger.info("Service Start PIN verified successfully for booking {} by worker {}", booking.getBookingId(), workerUserId);
        return true;
    }

    public void evictCustomerPin(UUID bookingId) {
        if (redisTemplate != null && bookingId != null) {
            try {
                redisTemplate.delete(REDIS_CUSTOMER_PIN_PREFIX + bookingId);
            } catch (Exception e) {
                logger.warn("Failed to evict customer PIN from Redis for booking {}: {}", bookingId, e.getMessage());
            }
        }
    }

    public Optional<BookingOtp> findActiveForBooking(UUID bookingId) {
        return otpRepository.findFirstByBooking_BookingIdAndStatus(bookingId, OtpStatus.ACTIVE);
    }

    private void notifyCustomerOfArrival(Booking booking, String rawOtp) {
        if (booking.getUser() == null) return;
        UUID customerId = booking.getUser().getUserId();

        // 1. FCM Push to Customer
        try {
            fcmNotificationService.sendPushNotification(
                    customerId,
                    "Service Start OTP: " + rawOtp,
                    "Your partner has arrived. Share PIN " + rawOtp + " to start the service."
            );
        } catch (Exception e) {
            logger.warn("Failed to dispatch customer FCM for arrival: {}", e.getMessage());
        }

        // 2. Real-time WebSocket to Customer's authenticated private channel
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "START_PIN_GENERATED");
            payload.put("bookingId", booking.getBookingId().toString());
            payload.put("pinCode", rawOtp);
            payload.put("status", "ARRIVED");
            messagingTemplate.convertAndSend("/topic/customer/" + customerId + "/booking", payload);
        } catch (Exception e) {
            logger.debug("WebSocket broadcast skipped in private channel dispatch: {}", e.getMessage());
        }
    }

    public String hashOtp(String otp, UUID bookingId) {
        String input = otp + ":" + (bookingId != null ? bookingId.toString() : "");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(
                    (hmacSecret != null ? hmacSecret : "HomeEase-Secure-Doorstep-Pin-Secret-2026-X9A7")
                            .getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            );
            mac.init(secretKey);
            byte[] hmacBytes = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hmacBytes.length * 2);
            for (byte b : hmacBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("Failed to calculate HMAC-SHA256 for OTP", e);
        }
    }

    public String hashValue(String value) {
        return sha256Hex(value != null ? value : "UNKNOWN");
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    private boolean isValidOtpFormat(String digits) {
        return digits != null && digits.trim().matches("\\d{4}");
    }
}
