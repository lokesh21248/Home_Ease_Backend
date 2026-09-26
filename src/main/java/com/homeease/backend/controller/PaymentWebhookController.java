package com.homeease.backend.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping({"/api/v1/payments", "/api/payments"})
public class PaymentWebhookController {

    private static final Logger logger = LoggerFactory.getLogger(PaymentWebhookController.class);

    @PostMapping({"/webhooks", "/webhook"})
    public ResponseEntity<String> handlePaymentWebhook(
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
            @RequestBody Map<String, Object> payload) {

        logger.info("Received Payment Webhook notification. Signature: {}", signature);
        logger.debug("Webhook Payload: {}", payload);

        return ResponseEntity.ok("Payment Webhook Event Acknowledged");
    }
}
