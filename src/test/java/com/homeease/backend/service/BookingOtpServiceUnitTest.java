package com.homeease.backend.service;

import com.homeease.backend.model.entity.Booking;
import com.homeease.backend.model.entity.BookingOtp;
import com.homeease.backend.model.entity.User;
import com.homeease.backend.model.entity.Worker;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.OtpStatus;
import com.homeease.backend.repository.BookingOtpRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class BookingOtpServiceUnitTest {

    @Mock private BookingOtpRepository otpRepository;
    @Mock private FcmNotificationService fcmNotificationService;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private BookingOtpService otpService;

    private UUID bookingId;
    private UUID customerId;
    private UUID workerUserId;
    private UUID workerId;
    private Booking testBooking;
    private User testCustomer;
    private Worker testWorker;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        otpService = new BookingOtpService(
                otpRepository,
                fcmNotificationService,
                messagingTemplate,
                redisTemplate
        );

        bookingId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        workerUserId = UUID.randomUUID();
        workerId = UUID.randomUUID();

        testCustomer = User.builder()
                .userId(customerId)
                .fullName("Anita Sharma")
                .phoneNumber("9876543210")
                .build();

        testWorker = Worker.builder()
                .workerId(workerId)
                .user(User.builder().userId(workerUserId).fullName("Ram Singh").build())
                .build();

        testBooking = Booking.builder()
                .bookingId(bookingId)
                .status(BookingStage.ARRIVED)
                .user(testCustomer)
                .worker(testWorker)
                .pinCode("****")
                .build();
    }

    @Test
    @DisplayName("issueOtp creates secure hash and stores plaintext in Redis with TTL")
    void testIssueOtpSecure() {
        otpService.issueOtp(testBooking);

        verify(otpRepository).save(argThat(otp -> {
            assertNotNull(otp.getHashedOtp());
            assertEquals(64, otp.getHashedOtp().length()); // SHA-256 hex length
            assertEquals(0, otp.getAttempts());
            assertEquals(OtpStatus.ACTIVE, otp.getStatus());
            return true;
        }));

        verify(valueOperations).set(
                eq("booking:customer_pin:" + bookingId),
                matches("^[0-9]{4}$"),
                eq(Duration.ofMinutes(15))
        );
    }

    @Test
    @DisplayName("verifyOtp succeeds with matching PIN when ARRIVED")
    void testVerifyOtpSuccess() {
        String rawPin = "5821";
        String hash = otpService.hashOtp(rawPin, bookingId);

        BookingOtp otp = BookingOtp.builder()
                .booking(testBooking)
                .hashedOtp(hash)
                .attempts(0)
                .maxAttempts(5)
                .status(OtpStatus.ACTIVE)
                .expiresAt(Instant.now().plusSeconds(900))
                .build();

        when(otpRepository.findFirstByBooking_BookingIdAndStatus(bookingId, OtpStatus.ACTIVE))
                .thenReturn(Optional.of(otp));

        boolean verified = otpService.verifyOtp(testBooking, workerUserId, rawPin);

        assertTrue(verified);
        assertEquals(OtpStatus.USED, otp.getStatus());
        verify(redisTemplate).delete("booking:customer_pin:" + bookingId);
    }

    @Test
    @DisplayName("verifyOtp rejects when booking is not in ARRIVED status")
    void testVerifyOtpFailsWhenNotArrived() {
        testBooking.setStatus(BookingStage.ACCEPTED);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                otpService.verifyOtp(testBooking, workerUserId, "5821")
        );

        assertTrue(ex.getMessage().contains("Partner must mark ARRIVED first"));
    }

    @Test
    @DisplayName("verifyOtp decrements remaining attempts and locks out after max failed attempts")
    void testVerifyOtpLockout() {
        String correctHash = otpService.hashOtp("1234", bookingId);

        BookingOtp otp = BookingOtp.builder()
                .booking(testBooking)
                .hashedOtp(correctHash)
                .attempts(4)
                .maxAttempts(5)
                .status(OtpStatus.ACTIVE)
                .expiresAt(Instant.now().plusSeconds(900))
                .build();

        when(otpRepository.findFirstByBooking_BookingIdAndStatus(bookingId, OtpStatus.ACTIVE))
                .thenReturn(Optional.of(otp));

        // 5th attempt with wrong PIN -> should lock out
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                otpService.verifyOtp(testBooking, workerUserId, "9999")
        );

        assertTrue(ex.getMessage().contains("Attempts remaining: 0"));
        assertEquals(5, otp.getAttempts());
        assertEquals(OtpStatus.REVOKED, otp.getStatus());
        verify(redisTemplate).delete("booking:customer_pin:" + bookingId);

        // Next attempt -> immediately blocked with lockout message
        IllegalStateException lockedEx = assertThrows(IllegalStateException.class, () ->
                otpService.verifyOtp(testBooking, workerUserId, "1234")
        );
        assertTrue(lockedEx.getMessage().contains("Maximum verification attempts exceeded"));
    }
}
