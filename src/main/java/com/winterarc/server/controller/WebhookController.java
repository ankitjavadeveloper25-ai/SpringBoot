package com.winterarc.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.winterarc.server.service.PaymentWebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {
    private static final Logger logger = LoggerFactory.getLogger(WebhookController.class);

    private final PaymentWebhookService webhookService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WebhookController(PaymentWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/stripe")
    public ResponseEntity<?> handleStripeWebhook(
        @RequestBody String payload,
        @RequestHeader(value = "Stripe-Signature", required = false) String sigHeader) {

        if (sigHeader == null || sigHeader.isBlank()) {
            logger.warn("Received Stripe webhook with missing Stripe-Signature header.");
            return ResponseEntity.status(400).body(Map.of("error", "Missing Stripe-Signature header"));
        }

        if (!webhookService.verifyStripeSignature(payload, sigHeader)) {
            logger.warn("Received Stripe webhook with invalid signature.");
            return ResponseEntity.status(400).body(Map.of("error", "Invalid signature"));
        }

        try {
            JsonNode root = objectMapper.readTree(payload);
            String eventId = root.path("id").asText();
            String eventType = root.path("type").asText();

            logger.info("Received valid Stripe webhook event: id={}, type={}", eventId, eventType);
            boolean ok = webhookService.processWebhookEvent("STRIPE", eventId, eventType, payload);
            return ok ? ResponseEntity.ok(Map.of("received", true)) : ResponseEntity.status(500).build();
        } catch (Exception e) {
            logger.error("Failed to parse Stripe webhook: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid JSON payload"));
        }
    }

    @PostMapping("/razorpay")
    public ResponseEntity<?> handleRazorpayWebhook(
        @RequestBody String payload,
        @RequestHeader(value = "X-Razorpay-Signature", required = false) String sigHeader,
        @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventIdHeader) {

        if (sigHeader == null || sigHeader.isBlank()) {
            logger.warn("Received Razorpay webhook with missing X-Razorpay-Signature header.");
            return ResponseEntity.status(400).body(Map.of("error", "Missing X-Razorpay-Signature header"));
        }

        if (!webhookService.verifyRazorpaySignature(payload, sigHeader)) {
            logger.warn("Received Razorpay webhook with invalid signature.");
            return ResponseEntity.status(400).body(Map.of("error", "Invalid signature"));
        }

        try {
            JsonNode root = objectMapper.readTree(payload);

            String eventId = eventIdHeader;
            if (eventId == null || eventId.isBlank()) {
                eventId = root.path("event_id").asText(null);
            }
            if (eventId == null || eventId.isBlank()) {
                eventId = root.path("id").asText(null);
            }
            if (eventId == null || eventId.isBlank()) {
                // Synthesize a deterministic event id from payment id + event if available
                String payId = root.path("payload").path("payment").path("entity").path("id").asText("");
                String evt = root.path("event").asText("");
                if (!payId.isEmpty()) {
                    eventId = "rzp_evt_" + payId + "_" + evt;
                } else {
                    eventId = "rzp_evt_" + System.currentTimeMillis();
                }
            }

            String eventType = root.path("event").asText(root.path("type").asText("unknown"));

            logger.info("Received valid Razorpay webhook event: id={}, type={}", eventId, eventType);
            boolean ok = webhookService.processWebhookEvent("RAZORPAY", eventId, eventType, payload);
            return ok ? ResponseEntity.ok(Map.of("received", true)) : ResponseEntity.status(500).build();
        } catch (Exception e) {
            logger.error("Failed to parse Razorpay webhook payload: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid JSON payload"));
        }
    }
}
