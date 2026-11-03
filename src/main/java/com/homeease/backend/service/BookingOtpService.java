package com.homeease.backend.service;

import com.homeease.backend.model.entity.Booking;
import com.homeease.backend.model.entity.BookingOtp;
import com.homeease.backend.model.enums.OtpStatus;
import com.homeease.backend.repository.BookingOtpRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

@Service
public class BookingOtpService {

    private static final Logger logger = LoggerFactory.getLogger(BookingOtpService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final BookingOtpRepository otpRepository;
    private final FcmNotificationService fcmNotificationService;
    private final SimpMessagingTemplate messagingTemplate;

    @Value("${homeease.booking.otp.ttl-seconds:900}") // 15 mins
    private int otpTtlSeconds = 900;

    @Value("${homeease.booking.otp.max-attempts:5}")
    private int maxAttempts = 5;

    @Value("${homeease.booking.otp.resend-cooldown-seconds:60}")
    private int resendCooldownSeconds = 60;

    public BookingOtpService(BookingOtpRepository otpRepository,
                             FcmNotificationService fcmNotificationService,
                             SimpMessagingTemplate messagingTemplate) {
        this.otpRepository = otpRepository;
        this.fcmNotificationService = fcmNotificationService;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Issues a new 4-digit OTP for the booking upon worker arrival.
     * Plaintext is dispatched ONLY to the customer via FCM & WebSocket and NEVER returned to the worker.
     */
    @Transactional
    public BookingOtp issueOtp(Booking booking) {
        if (booking == null) {
            throw new IllegalArgumentException("Booking cannot be null");
        }

        // Check if active OTP already exists and cooldown has not elapsed
        Optional<BookingOtp> existing = otpRepository.findFirstByBooking_BookingIdAndStatus(
                booking.getBookingId(), OtpStatus.ACTIVE);

        Instant now = Instant.now();
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

        String rawOtp = (booking.getPinCode() != null && !booking.getPinCode().isBlank())
                ? booking.getPinCode().trim()
                : String.format("%04d", SECURE_RANDOM.nextInt(10000));
        if (booking.getPinCode() == null || booking.getPinCode().isBlank()) {
            booking.setPinCode(rawOtp);
        }
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

        // Deliver plaintext OTP to CUSTOMER only
        sendOtpToCustomer(booking, rawOtp);
        logger.info("Successfully issued 4-digit OTP for booking {}", booking.getBookingId());
        return otpEntity;
    }

    /**
     * Assigned worker submits the customer-provided 4-digit PIN/OTP.
     * Verifies against secure hash, enforces attempt limits and expiration.
     */
    @Transactional
    public boolean verifyOtp(Booking booking, UUID workerUserId, String submittedOtp) {
        if (booking == null || submittedOtp == null || !isValidOtpFormat(submittedOtp)) {
            throw new IllegalArgumentException("Invalid OTP format. Must be 4 numeric digits.");
        }

        // Worker ownership check: only the assigned worker can verify OTP
        if (booking.getWorker() == null || booking.getWorker().getUser() == null ||
                !booking.getWorker().getUser().getUserId().equals(workerUserId)) {
            throw new SecurityException("Unauthorized: Only the assigned worker may verify OTP for this booking.");
        }

        Instant now = Instant.now();
        String trimmedOtp = submittedOtp.trim();

        // 1. Direct canonical fallback match against booking.pinCode
        boolean matchesPinCode = booking.getPinCode() != null &&
                booking.getPinCode().trim().equals(trimmedOtp);

        Optional<BookingOtp> otpOpt = otpRepository.findFirstByBooking_BookingIdAndStatus(
                booking.getBookingId(), OtpStatus.ACTIVE);

        if (otpOpt.isEmpty()) {
            if (matchesPinCode) {
                // Synthesize USED record to log successful verification
                String customerPhone = booking.getRecipientPhone() != null && !booking.getRecipientPhone().isBlank()
                        ? booking.getRecipientPhone()
                        : (booking.getUser() != null ? booking.getUser().getPhoneNumber() : "UNKNOWN");
                BookingOtp syn = BookingOtp.builder()
                        .booking(booking)
                        .hashedOtp(hashOtp(trimmedOtp, booking.getBookingId()))
                        .phoneHash(hashValue(customerPhone))
                        .expiresAt(now.plusSeconds(otpTtlSeconds))
                        .maxAttempts(maxAttempts)
                        .attempts(1)
                        .resendCooldownUntil(now)
                        .status(OtpStatus.USED)
                        .build();
                otpRepository.save(syn);
                logger.info("Canonical PIN verified successfully for booking {} by worker {}", booking.getBookingId(), workerUserId);
                return true;
            }
            throw new IllegalStateException("No active OTP found for this booking.");
        }

        BookingOtp otp = otpOpt.get();

        // Check expiration
        if (otp.getExpiresAt().isBefore(now)) {
            otp.setStatus(OtpStatus.EXPIRED);
            otp.setUpdatedAt(now);
            otpRepository.save(otp);
            throw new IllegalStateException("OTP has expired. Please ask customer to request a new OTP.");
        }

        // Check brute-force attempts
        if (otp.getAttempts() >= otp.getMaxAttempts()) {
            otp.setStatus(OtpStatus.REVOKED);
            otp.setUpdatedAt(now);
            otpRepository.save(otp);
            throw new IllegalStateException("Maximum verification attempts exceeded. OTP is locked.");
        }

        String computedHash = hashOtp(trimmedOtp, booking.getBookingId());
        boolean matchesHash = MessageDigest.isEqual(
                computedHash.getBytes(StandardCharsets.UTF_8),
                otp.getHashedOtp().getBytes(StandardCharsets.UTF_8)
        );

        if (!matchesHash && !matchesPinCode) {
            otp.setAttempts(otp.getAttempts() + 1);
            otp.setUpdatedAt(now);
            if (otp.getAttempts() >= otp.getMaxAttempts()) {
                otp.setStatus(OtpStatus.REVOKED);
            }
            otpRepository.save(otp);
            int remaining = Math.max(0, otp.getMaxAttempts() - otp.getAttempts());
            throw new IllegalArgumentException("Incorrect OTP. Attempts remaining: " + remaining);
        }

        // Verified successfully: mark OTP as USED
        otp.setStatus(OtpStatus.USED);
        otp.setUpdatedAt(now);
        otpRepository.save(otp);
        logger.info("OTP verified successfully for booking {} by worker {}", booking.getBookingId(), workerUserId);
        return true;
    }

    public Optional<BookingOtp> findActiveForBooking(UUID bookingId) {
        return otpRepository.findFirstByBooking_BookingIdAndStatus(bookingId, OtpStatus.ACTIVE);
    }

    private void sendOtpToCustomer(Booking booking, String rawOtp) {
        if (booking.getUser() == null) return;
        UUID customerId = booking.getUser().getUserId();

        // 1. FCM Push to Customer
        try {
            fcmNotificationService.sendPushNotification(
                    customerId,
                    "Service Start OTP: " + rawOtp,
                    "Your partner has arrived. Share OTP " + rawOtp + " to start the service."
            );
        } catch (Exception e) {
            logger.warn("Failed to dispatch customer FCM for arrival OTP: {}", e.getMessage());
        }

        // 2. Real-time WebSocket to Customer authenticated topic
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "START_OTP_GENERATED");
            payload.put("bookingId", booking.getBookingId().toString());
            payload.put("otp", rawOtp);
            payload.put("status", "ARRIVED");
            messagingTemplate.convertAndSend("/topic/booking/" + booking.getBookingId(), payload);
            messagingTemplate.convertAndSend("/topic/customer/" + customerId + "/booking", payload);
        } catch (Exception e) {
            logger.debug("WebSocket broadcast skipped in OTP dispatch: {}", e.getMessage());
        }
    }

    public String hashOtp(String otp, UUID bookingId) {
        String input = otp + ":" + bookingId.toString();
        return sha256Hex(input);
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
