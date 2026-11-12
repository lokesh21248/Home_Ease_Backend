package com.homeease.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.model.entity.Payment;
import com.homeease.backend.model.enums.TransactionState;
import com.homeease.backend.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * Razorpay payment webhook handler.
 *
 * <p>Verifies HMAC-SHA256 signature before processing any state change.
 * Configure {@code RAZORPAY_WEBHOOK_SECRET} in /etc/homeease/app.env on EC2.
 *
 * <p>Signature algorithm:
 * {@code HMAC-SHA256(razorpay_webhook_secret, raw_request_body)}
 * compared against {@code X-Razorpay-Signature} header (hex-encoded).
 */
@RestController
@RequestMapping({"/api/v1/payments", "/api/payments"})
public class PaymentWebhookController {

    private static final Logger logger = LoggerFactory.getLogger(PaymentWebhookController.class);

    private final PaymentRepository paymentRepository;
    private final ObjectMapper objectMapper;

    @Value("${homeease.razorpay.webhook-secret:}")
    private String webhookSecret;

    public PaymentWebhookController(PaymentRepository paymentRepository, ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.objectMapper = objectMapper;
    }

    @PostMapping({"/webhooks", "/webhook"})
    public ResponseEntity<String> handlePaymentWebhook(
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String razorpaySignature,
            @RequestBody byte[] rawBody) {

        // --- 1. Signature verification (skip only when secret is not yet configured) ---
        if (webhookSecret != null && !webhookSecret.isBlank()) {
            if (razorpaySignature == null || razorpaySignature.isBlank()) {
                logger.warn("WEBHOOK_REJECTED: missing X-Razorpay-Signature header");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing webhook signature");
            }
            if (!verifySignature(rawBody, razorpaySignature, webhookSecret)) {
                logger.warn("WEBHOOK_REJECTED: signature mismatch");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid webhook signature");
            }
        } else {
            logger.warn("WEBHOOK_SIGNATURE_SKIPPED: homeease.razorpay.webhook-secret is not configured — " +
                        "set RAZORPAY_WEBHOOK_SECRET in /etc/homeease/app.env and restart");
        }

        // --- 2. Parse event ---
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            String event = root.path("event").asText("");
            logger.info("WEBHOOK_RECEIVED event={}", event);

            if ("payment.captured".equals(event) || "order.paid".equals(event)) {
                processPaymentSuccess(root, event);
            } else if ("payment.failed".equals(event)) {
                processPaymentFailed(root);
            } else {
                logger.debug("WEBHOOK_IGNORED unhandled event={}", event);
            }
        } catch (Exception e) {
            logger.error("WEBHOOK_PARSE_ERROR: {}", e.getMessage(), e);
            // Return 200 so Razorpay does not retry on our parse errors
        }

        return ResponseEntity.ok("OK");
    }

    private void processPaymentSuccess(JsonNode root, String event) {
        try {
            JsonNode entity = root.path("payload").path("payment").path("entity");
            String orderId    = entity.path("order_id").asText("");
            String paymentId  = entity.path("id").asText("");
            String razorpayStatus = entity.path("status").asText(""); // "captured"

            if (orderId.isBlank() && paymentId.isBlank()) {
                logger.warn("WEBHOOK_SUCCESS_SKIP: no order_id or payment_id in event={}", event);
                return;
            }

            // Look up by orderId (stored as transactionRef at order-creation time)
            String ref = orderId.isBlank() ? paymentId : orderId;
            Optional<Payment> paymentOpt = paymentRepository.findByTransactionRef(ref);
            if (paymentOpt.isEmpty()) {
                logger.warn("WEBHOOK_PAYMENT_NOT_FOUND ref={} event={}", ref, event);
                return;
            }

            Payment payment = paymentOpt.get();
            if (payment.getStatus() == TransactionState.SUCCESS) {
                logger.info("WEBHOOK_ALREADY_CAPTURED paymentId={} ref={}", payment.getPaymentId(), ref);
                return; // idempotent
            }

            payment.setStatus(TransactionState.SUCCESS);
            paymentRepository.save(payment);

            logger.info("WEBHOOK_PAYMENT_CAPTURED paymentId={} orderId={} razorpayPaymentId={} bookingId={}",
                    payment.getPaymentId(), orderId, paymentId, payment.getBooking().getBookingId());

        } catch (Exception e) {
            logger.error("WEBHOOK_SUCCESS_PROCESSING_ERROR: {}", e.getMessage(), e);
        }
    }

    private void processPaymentFailed(JsonNode root) {
        try {
            JsonNode entity = root.path("payload").path("payment").path("entity");
            String orderId   = entity.path("order_id").asText("");
            String paymentId = entity.path("id").asText("");
            String errorDesc = root.path("payload").path("payment").path("entity")
                    .path("error_description").asText("unknown");

            String ref = orderId.isBlank() ? paymentId : orderId;
            Optional<Payment> paymentOpt = paymentRepository.findByTransactionRef(ref);
            if (paymentOpt.isEmpty()) {
                logger.warn("WEBHOOK_FAILED_PAYMENT_NOT_FOUND ref={}", ref);
                return;
            }
            Payment payment = paymentOpt.get();
            if (payment.getStatus() == TransactionState.FAILED) {
                return; // idempotent
            }
            payment.setStatus(TransactionState.FAILED);
            paymentRepository.save(payment);

            logger.info("WEBHOOK_PAYMENT_FAILED paymentId={} ref={} reason={} bookingId={}",
                    payment.getPaymentId(), ref, errorDesc, payment.getBooking().getBookingId());

        } catch (Exception e) {
            logger.error("WEBHOOK_FAILED_PROCESSING_ERROR: {}", e.getMessage(), e);
        }
    }

    /**
     * Verifies Razorpay HMAC-SHA256 webhook signature.
     * Razorpay signs: HMAC_SHA256(webhook_secret, raw_request_body)
     */
    private boolean verifySignature(byte[] rawBody, String receivedSignature, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] computed = mac.doFinal(rawBody);
            String computedHex = HexFormat.of().formatHex(computed);
            return computedHex.equalsIgnoreCase(receivedSignature.trim());
        } catch (Exception e) {
            logger.error("WEBHOOK_SIGNATURE_ERROR: {}", e.getMessage());
            return false;
        }
    }
}
