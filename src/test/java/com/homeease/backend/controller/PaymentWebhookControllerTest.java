package com.homeease.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.model.entity.Booking;
import com.homeease.backend.model.entity.Payment;
import com.homeease.backend.model.enums.TransactionState;
import com.homeease.backend.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentWebhookControllerTest {

    @Mock
    private PaymentRepository paymentRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PaymentWebhookController controller;

    private static final String TEST_SECRET = "test_razorpay_webhook_secret_key_12345";

    @BeforeEach
    void setUp() {
        controller = new PaymentWebhookController(paymentRepository, objectMapper);
        ReflectionTestUtils.setField(controller, "webhookSecret", TEST_SECRET);
    }

    private String calculateSignature(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test
    @DisplayName("Webhook fails with 401 when signature header is missing")
    void missingSignatureHeaderReturns401() {
        byte[] body = "{\"event\":\"payment.captured\"}".getBytes(StandardCharsets.UTF_8);

        ResponseEntity<String> response = controller.handlePaymentWebhook(null, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("Missing webhook signature");
        verifyNoInteractions(paymentRepository);
    }

    @Test
    @DisplayName("Webhook fails with 401 when signature is invalid/tampered")
    void invalidSignatureReturns401() {
        byte[] body = "{\"event\":\"payment.captured\"}".getBytes(StandardCharsets.UTF_8);
        String badSignature = "bad_signature_deadbeef1234567890abcdef";

        ResponseEntity<String> response = controller.handlePaymentWebhook(badSignature, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("Invalid webhook signature");
        verifyNoInteractions(paymentRepository);
    }

    @Test
    @DisplayName("Webhook succeeds and updates payment status on valid payment.captured")
    void validPaymentCapturedUpdatesStatus() throws Exception {
        String orderId = "order_OId1234567890";
        String paymentId = "pay_PId1234567890";
        String payload = """
            {
              "event": "payment.captured",
              "payload": {
                "payment": {
                  "entity": {
                    "id": "%s",
                    "order_id": "%s",
                    "status": "captured"
                  }
                }
              }
            }
            """.formatted(paymentId, orderId);

        byte[] rawBody = payload.getBytes(StandardCharsets.UTF_8);
        String signature = calculateSignature(rawBody, TEST_SECRET);

        Booking booking = Booking.builder().bookingId(UUID.randomUUID()).build();
        Payment payment = Payment.builder()
                .paymentId(UUID.randomUUID())
                .booking(booking)
                .transactionRef(orderId)
                .status(TransactionState.PENDING)
                .build();

        when(paymentRepository.findByTransactionRef(orderId)).thenReturn(Optional.of(payment));

        ResponseEntity<String> response = controller.handlePaymentWebhook(signature, rawBody);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("OK");
        assertThat(payment.getStatus()).isEqualTo(TransactionState.SUCCESS);
        verify(paymentRepository).save(payment);
    }

    @Test
    @DisplayName("Duplicate webhook delivery is idempotent and does not re-save SUCCESS payment")
    void duplicateSuccessWebhookIsIdempotent() throws Exception {
        String orderId = "order_OId1234567890";
        String payload = """
            {
              "event": "payment.captured",
              "payload": {
                "payment": {
                  "entity": {
                    "id": "pay_PId1234567890",
                    "order_id": "%s",
                    "status": "captured"
                  }
                }
              }
            }
            """.formatted(orderId);

        byte[] rawBody = payload.getBytes(StandardCharsets.UTF_8);
        String signature = calculateSignature(rawBody, TEST_SECRET);

        Payment alreadySuccessful = Payment.builder()
                .paymentId(UUID.randomUUID())
                .transactionRef(orderId)
                .status(TransactionState.SUCCESS)
                .build();

        when(paymentRepository.findByTransactionRef(orderId)).thenReturn(Optional.of(alreadySuccessful));

        ResponseEntity<String> response = controller.handlePaymentWebhook(signature, rawBody);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    @DisplayName("payment.failed marks payment status as FAILED")
    void paymentFailedUpdatesStatus() throws Exception {
        String orderId = "order_Fail123";
        String payload = """
            {
              "event": "payment.failed",
              "payload": {
                "payment": {
                  "entity": {
                    "id": "pay_Fail123",
                    "order_id": "%s",
                    "error_description": "Insufficient funds"
                  }
                }
              }
            }
            """.formatted(orderId);

        byte[] rawBody = payload.getBytes(StandardCharsets.UTF_8);
        String signature = calculateSignature(rawBody, TEST_SECRET);

        Booking booking = Booking.builder().bookingId(UUID.randomUUID()).build();
        Payment payment = Payment.builder()
                .paymentId(UUID.randomUUID())
                .booking(booking)
                .transactionRef(orderId)
                .status(TransactionState.PENDING)
                .build();

        when(paymentRepository.findByTransactionRef(orderId)).thenReturn(Optional.of(payment));

        ResponseEntity<String> response = controller.handlePaymentWebhook(signature, rawBody);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(payment.getStatus()).isEqualTo(TransactionState.FAILED);
        verify(paymentRepository).save(payment);
    }

    @Test
    @DisplayName("Duplicate payment.failed is idempotent and does not re-save")
    void duplicatePaymentFailedIsIdempotent() throws Exception {
        String orderId = "order_Fail123";
        String payload = """
            {
              "event": "payment.failed",
              "payload": {
                "payment": {
                  "entity": {
                    "id": "pay_Fail123",
                    "order_id": "%s",
                    "error_description": "Insufficient funds"
                  }
                }
              }
            }
            """.formatted(orderId);

        byte[] rawBody = payload.getBytes(StandardCharsets.UTF_8);
        String signature = calculateSignature(rawBody, TEST_SECRET);

        Payment alreadyFailed = Payment.builder()
                .paymentId(UUID.randomUUID())
                .transactionRef(orderId)
                .status(TransactionState.FAILED)
                .build();

        when(paymentRepository.findByTransactionRef(orderId)).thenReturn(Optional.of(alreadyFailed));

        ResponseEntity<String> response = controller.handlePaymentWebhook(signature, rawBody);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    @DisplayName("Unhandled events return 200 OK without modifying payment")
    void unhandledEventReturns200WithoutModifications() throws Exception {
        String payload = "{\"event\": \"refund.processed\"}";
        byte[] rawBody = payload.getBytes(StandardCharsets.UTF_8);
        String signature = calculateSignature(rawBody, TEST_SECRET);

        ResponseEntity<String> response = controller.handlePaymentWebhook(signature, rawBody);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(paymentRepository);
    }

    @Test
    @DisplayName("Payment not found for orderId logs warning and returns 200 without crash")
    void paymentNotFoundHandledGracefully() throws Exception {
        String orderId = "order_NonExistent";
        String payload = """
            {
              "event": "payment.captured",
              "payload": {
                "payment": {
                  "entity": {
                    "id": "pay_123",
                    "order_id": "%s",
                    "status": "captured"
                  }
                }
              }
            }
            """.formatted(orderId);

        byte[] rawBody = payload.getBytes(StandardCharsets.UTF_8);
        String signature = calculateSignature(rawBody, TEST_SECRET);

        when(paymentRepository.findByTransactionRef(orderId)).thenReturn(Optional.empty());

        ResponseEntity<String> response = controller.handlePaymentWebhook(signature, rawBody);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(paymentRepository, never()).save(any(Payment.class));
    }
}
