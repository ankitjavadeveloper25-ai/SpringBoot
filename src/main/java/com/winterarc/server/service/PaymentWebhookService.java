package com.winterarc.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.winterarc.server.config.RazorpayConfig;
import com.winterarc.server.model.*;
import com.winterarc.server.repository.PaymentOrderRepository;
import com.winterarc.server.repository.PaymentTransactionRepository;
import com.winterarc.server.repository.SubscriptionRepository;
import com.winterarc.server.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class PaymentWebhookService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentWebhookService.class);

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final RazorpayPaymentService razorpayPaymentService;
    private final RazorpayGateway razorpayGateway;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String stripeWebhookSecret;
    private final String razorpayWebhookSecret;

    public PaymentWebhookService(
            UserRepository userRepository,
            SubscriptionRepository subscriptionRepository,
            PaymentTransactionRepository paymentTransactionRepository,
            PaymentOrderRepository paymentOrderRepository,
            RazorpayPaymentService razorpayPaymentService,
            RazorpayGateway razorpayGateway,
            RazorpayConfig razorpayConfig,
            @Value("${winterarc.webhooks.stripe-webhook-secret:whsec_stripe_winterarc_prod_secret_key_test_123}") String stripeWebhookSecret,
            @Value("${winterarc.webhooks.razorpay-webhook-secret:rzp_sec_placeholder_webhook_secret}") String razorpayWebhookSecret) {
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.paymentOrderRepository = paymentOrderRepository;
        this.razorpayPaymentService = razorpayPaymentService;
        this.razorpayGateway = razorpayGateway;
        this.stripeWebhookSecret = stripeWebhookSecret;
        this.razorpayWebhookSecret = (razorpayConfig.getWebhookSecret() != null && !razorpayConfig.getWebhookSecret().isBlank())
                ? razorpayConfig.getWebhookSecret() : razorpayWebhookSecret;
    }

    public boolean verifyStripeSignature(String payload, String sigHeader) {
        if (sigHeader == null || !sigHeader.contains("t=") || !sigHeader.contains("v1=")) {
            return false;
        }
        try {
            String timestamp = "";
            String signature = "";
            for (String part : sigHeader.split(",")) {
                String[] kv = part.trim().split("=");
                if (kv.length == 2) {
                    if (kv[0].equals("t")) timestamp = kv[1];
                    if (kv[0].equals("v1")) signature = kv[1];
                }
            }
            if (timestamp.isEmpty() || signature.isEmpty()) return false;

            String signedPayload = timestamp + "." + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(stripeWebhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] computed = mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
            String computedHex = HexFormat.of().formatHex(computed);

            return MessageDigest.isEqual(computedHex.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            logger.error("Error verifying Stripe signature", e);
            return false;
        }
    }

    public boolean verifyRazorpaySignature(String payload, String signature) {
        if (signature == null || signature.isBlank()) return false;
        return razorpayGateway.verifyWebhookSignature(payload, signature);
    }

    @Transactional
    public boolean processWebhookEvent(String provider, String eventId, String eventType, String rawPayload) {
        if (eventId == null || eventId.isBlank()) {
            logger.warn("Received webhook with missing eventId. Cannot process.");
            return false;
        }

        if (paymentTransactionRepository.existsByEventId(eventId)) {
            logger.info("Webhook event {} already processed (Idempotent ignore).", eventId);
            return true;
        }

        try {
            JsonNode root = objectMapper.readTree(rawPayload);

            if ("RAZORPAY".equalsIgnoreCase(provider)) {
                return processRazorpayEvent(eventId, eventType, root, rawPayload);
            } else {
                return processStripeEvent(eventId, eventType, root, rawPayload);
            }
        } catch (Exception e) {
            logger.error("Error processing webhook event: {}", eventId, e);
            return false;
        }
    }

    private boolean processRazorpayEvent(String eventId, String eventType, JsonNode root, String rawPayload) {
        switch (eventType) {
            case "payment.captured" -> {
                JsonNode paymentEntity = root.path("payload").path("payment").path("entity");
                String paymentId = paymentEntity.path("id").asText(null);
                String orderId = paymentEntity.path("order_id").asText(null);
                long amount = paymentEntity.path("amount").asLong(0);
                String currency = paymentEntity.path("currency").asText("INR");

                if (orderId == null || orderId.isBlank()) {
                    String subId = paymentEntity.path("subscription_id").asText(null);
                    if (subId != null && !subId.isBlank()) {
                        orderId = subId;
                    }
                }

                Optional<PaymentOrder> orderOpt = orderId != null
                        ? paymentOrderRepository.findByRazorpayOrderId(orderId)
                        : Optional.empty();

                if (orderOpt.isEmpty()) {
                    String internalOrderId = paymentEntity.path("notes").path("internalOrderId").asText(null);
                    if (internalOrderId != null) {
                        orderOpt = paymentOrderRepository.findById(internalOrderId);
                    }
                }

                if (orderOpt.isEmpty()) {
                    logger.warn("QUARANTINE: Razorpay payment.captured event {} references unknown orderId '{}'. Quarantining event without creating user/subscription.",
                            eventId, orderId);

                    PaymentTransaction quarantineTx = new PaymentTransaction(
                            null, null, "RAZORPAY", eventId, eventType, amount, currency, "UNMATCHED_ORDER", rawPayload
                    );
                    quarantineTx.setOrderId(orderId);
                    quarantineTx.setPaymentId(paymentId);
                    paymentTransactionRepository.save(quarantineTx);
                    return true;
                }

                PaymentOrder order = orderOpt.get();
                User user = order.getUser();

                if (order.getAmount() != amount || !order.getCurrency().equalsIgnoreCase(currency)) {
                    logger.warn("QUARANTINE: Webhook payment amount/currency mismatch for order {}. Expected {} {}, got {} {}",
                            order.getId(), order.getAmount(), order.getCurrency(), amount, currency);
                    PaymentTransaction mismatchTx = new PaymentTransaction(
                            user, null, "RAZORPAY", eventId, eventType, amount, currency, "AMOUNT_MISMATCH", rawPayload
                    );
                    mismatchTx.setOrderId(order.getRazorpayOrderId());
                    mismatchTx.setPaymentId(paymentId);
                    paymentTransactionRepository.save(mismatchTx);
                    return true;
                }

                Subscription sub;
                if (order.isPaid()) {
                    logger.info("Order {} is already PAID. Idempotent webhook receipt for paymentId: {}", order.getId(), paymentId);
                    sub = subscriptionRepository.findTopByUserOrderByCreatedAtDesc(user).orElse(null);
                } else {
                    order.setRazorpayPaymentId(paymentId);
                    order.setStatus("PAID");
                    order.setPaidAt(Instant.now());
                    order.setUpdatedAt(Instant.now());
                    paymentOrderRepository.save(order);

                    sub = razorpayPaymentService.activateSubscriptionForOrder(order, paymentId, "WEBHOOK_PAYMENT_CAPTURED");
                    logger.info("Activated subscription via webhook payment.captured: user={}, tier={}, order={}",
                            user.getEmail(), order.getPlan(), order.getRazorpayOrderId());
                }

                PaymentTransaction tx = new PaymentTransaction(
                        user, sub, "RAZORPAY", eventId, eventType, amount, currency, "SUCCEEDED", rawPayload
                );
                tx.setOrderId(order.getRazorpayOrderId());
                tx.setPaymentId(paymentId);
                paymentTransactionRepository.save(tx);
                return true;
            }

            case "order.paid" -> {
                JsonNode orderEntity = root.path("payload").path("order").path("entity");
                String orderId = orderEntity.path("id").asText(null);
                long amount = orderEntity.path("amount_paid").asLong(orderEntity.path("amount").asLong(0));
                String currency = orderEntity.path("currency").asText("INR");

                JsonNode paymentEntity = root.path("payload").path("payment").path("entity");
                String paymentId = paymentEntity.path("id").asText(null);

                Optional<PaymentOrder> orderOpt = orderId != null
                        ? paymentOrderRepository.findByRazorpayOrderId(orderId)
                        : Optional.empty();

                if (orderOpt.isEmpty()) {
                    logger.warn("QUARANTINE: Razorpay order.paid event {} references unknown orderId '{}'. Quarantining event.", eventId, orderId);
                    PaymentTransaction quarantineTx = new PaymentTransaction(
                            null, null, "RAZORPAY", eventId, eventType, amount, currency, "UNMATCHED_ORDER", rawPayload
                    );
                    quarantineTx.setOrderId(orderId);
                    quarantineTx.setPaymentId(paymentId);
                    paymentTransactionRepository.save(quarantineTx);
                    return true;
                }

                PaymentOrder order = orderOpt.get();
                User user = order.getUser();

                Subscription sub;
                if (!order.isPaid()) {
                    if (paymentId != null) order.setRazorpayPaymentId(paymentId);
                    order.setStatus("PAID");
                    order.setPaidAt(Instant.now());
                    order.setUpdatedAt(Instant.now());
                    paymentOrderRepository.save(order);

                    sub = razorpayPaymentService.activateSubscriptionForOrder(order, paymentId != null ? paymentId : orderId, "WEBHOOK_ORDER_PAID");
                    logger.info("Activated subscription via webhook order.paid: user={}, tier={}, order={}",
                            user.getEmail(), order.getPlan(), orderId);
                } else {
                    sub = subscriptionRepository.findTopByUserOrderByCreatedAtDesc(user).orElse(null);
                }

                PaymentTransaction tx = new PaymentTransaction(
                        user, sub, "RAZORPAY", eventId, eventType, amount, currency, "SUCCEEDED", rawPayload
                );
                tx.setOrderId(order.getRazorpayOrderId());
                tx.setPaymentId(paymentId);
                paymentTransactionRepository.save(tx);
                return true;
            }

            case "payment.failed" -> {
                JsonNode paymentEntity = root.path("payload").path("payment").path("entity");
                String orderId = paymentEntity.path("order_id").asText(null);
                String paymentId = paymentEntity.path("id").asText(null);
                long amount = paymentEntity.path("amount").asLong(0);
                String currency = paymentEntity.path("currency").asText("INR");

                Optional<PaymentOrder> orderOpt = orderId != null
                        ? paymentOrderRepository.findByRazorpayOrderId(orderId)
                        : Optional.empty();

                User user = null;
                Subscription sub = null;

                if (orderOpt.isPresent()) {
                    PaymentOrder order = orderOpt.get();
                    user = order.getUser();
                    sub = subscriptionRepository.findTopByUserOrderByCreatedAtDesc(user).orElse(null);

                    if (!order.isPaid()) {
                        order.setStatus("FAILED");
                        order.setUpdatedAt(Instant.now());
                        paymentOrderRepository.save(order);
                        logger.info("Marked PaymentOrder {} as FAILED following payment.failed webhook.", order.getRazorpayOrderId());
                    } else {
                        logger.warn("Ignoring payment.failed for already PAID order {} (out-of-order failure event).", order.getRazorpayOrderId());
                    }
                }

                PaymentTransaction tx = new PaymentTransaction(
                        user, sub, "RAZORPAY", eventId, eventType, amount, currency, "FAILED", rawPayload
                );
                tx.setOrderId(orderId);
                tx.setPaymentId(paymentId);
                paymentTransactionRepository.save(tx);
                return true;
            }

            case "refund.processed", "payment.refunded" -> {
                JsonNode refundEntity = root.path("payload").path("refund").path("entity");
                String paymentId = refundEntity.path("payment_id").asText(null);
                long amount = refundEntity.path("amount").asLong(0);
                String currency = refundEntity.path("currency").asText("INR");

                Optional<Subscription> subOpt = Optional.empty();
                if (paymentId != null) {
                    subOpt = subscriptionRepository.findByExternalSubscriptionId(paymentId);
                    if (subOpt.isEmpty()) {
                        Optional<PaymentOrder> poOpt = paymentOrderRepository.findByRazorpayPaymentId(paymentId);
                        if (poOpt.isPresent()) {
                            subOpt = subscriptionRepository.findByExternalSubscriptionId(poOpt.get().getRazorpayOrderId());
                            if (subOpt.isEmpty()) {
                                subOpt = subscriptionRepository.findTopByUserOrderByCreatedAtDesc(poOpt.get().getUser());
                            }
                        }
                    }
                    if (subOpt.isEmpty()) {
                        Optional<PaymentTransaction> txOpt = paymentTransactionRepository.findByPaymentId(paymentId);
                        if (txOpt.isPresent() && txOpt.get().getSubscription() != null) {
                            subOpt = Optional.of(txOpt.get().getSubscription());
                        }
                    }
                }

                User user = null;
                Subscription sub = null;

                if (subOpt.isPresent()) {
                    sub = subOpt.get();
                    user = sub.getUser();
                    sub.setStatus("REVOKED");
                    sub.setCancelledAt(Instant.now());
                    sub.setCurrentPeriodEnd(Instant.now());
                    subscriptionRepository.save(sub);
                    logger.info("Revoked refunded subscription for user: {}", user.getEmail());
                } else {
                    logger.warn("Refund webhook event for unknown paymentId/subscription: {}", paymentId);
                }

                PaymentTransaction tx = new PaymentTransaction(
                        user, sub, "RAZORPAY", eventId, eventType, amount, currency, "REFUNDED", rawPayload
                );
                tx.setPaymentId(paymentId);
                paymentTransactionRepository.save(tx);
                return true;
            }

            default -> {
                logger.info("Ignoring unhandled Razorpay webhook event: {}", eventType);
                PaymentTransaction tx = new PaymentTransaction(
                        null, null, "RAZORPAY", eventId, eventType, 0, "INR", "IGNORED", rawPayload
                );
                paymentTransactionRepository.save(tx);
                return true;
            }
        }
    }

    private boolean processStripeEvent(String eventId, String eventType, JsonNode root, String rawPayload) {
        User user = null;
        Subscription subscription = null;
        long amount = 0;
        String currency = "usd";
        String txStatus = "SUCCEEDED";

        switch (eventType) {
            case "checkout.session.completed" -> {
                String email = root.path("data").path("object").path("customer_email").asText(null);
                if (email == null || email.isBlank()) {
                    logger.warn("QUARANTINE: Stripe checkout session with missing customer_email. Ignoring without creating user.");
                    PaymentTransaction quarantine = new PaymentTransaction(
                            null, null, "STRIPE", eventId, eventType, 0, "USD", "UNMATCHED_USER", rawPayload
                    );
                    paymentTransactionRepository.save(quarantine);
                    return true;
                }
                final String userEmail = email.trim().toLowerCase();

                String planStr = root.path("data").path("object").path("metadata").path("plan").asText("pro_yearly");
                SubscriptionTier tier = parseTier(planStr);

                Optional<User> userOpt = userRepository.findByEmail(userEmail);
                user = userOpt.orElseGet(() -> userRepository.save(new User(userEmail, "hashed_pw", "Customer " + userEmail)));

                amount = root.path("data").path("object").path("amount_total").asLong(tier.getUsdCents());
                currency = root.path("data").path("object").path("currency").asText("usd");

                Instant start = Instant.now();
                Instant end = tier == SubscriptionTier.PRO_MONTHLY
                        ? start.plusSeconds(30L * 86400L)
                        : (tier == SubscriptionTier.PRO_YEARLY ? start.plusSeconds(365L * 86400L) : start.plusSeconds(100L * 365L * 86400L));

                String externalSubId = root.path("data").path("object").path("subscription").asText(eventId);

                subscription = new Subscription(user, tier, "ACTIVE", "STRIPE", start, end);
                subscription.setExternalSubscriptionId(externalSubId);
                subscription = subscriptionRepository.save(subscription);
                logger.info("Activated Stripe subscription: user={}, tier={}, subId={}", user.getEmail(), tier, externalSubId);
            }

            case "invoice.payment_succeeded" -> {
                String subId = root.path("data").path("object").path("subscription").asText("");
                Optional<Subscription> subOpt = subscriptionRepository.findByExternalSubscriptionId(subId);
                if (subOpt.isPresent()) {
                    subscription = subOpt.get();
                    user = subscription.getUser();
                    long extendDays = subscription.getTier() == SubscriptionTier.PRO_YEARLY ? 365L : 30L;
                    subscription.setCurrentPeriodEnd(subscription.getCurrentPeriodEnd().plusSeconds(extendDays * 86400L));
                    subscription.setStatus("ACTIVE");
                    subscriptionRepository.save(subscription);
                    logger.info("Renewed Stripe subscription for user: {}, new expiry: {}", user.getEmail(), subscription.getCurrentPeriodEnd());
                } else {
                    logger.warn("Renewal event for unknown external subscription id: {}", subId);
                }
            }

            case "customer.subscription.deleted" -> {
                String subId = root.path("data").path("object").path("id").asText("");
                Optional<Subscription> subOpt = subscriptionRepository.findByExternalSubscriptionId(subId);
                if (subOpt.isPresent()) {
                    subscription = subOpt.get();
                    user = subscription.getUser();
                    subscription.setStatus("CANCELLED");
                    subscription.setCancelledAt(Instant.now());
                    subscriptionRepository.save(subscription);
                    logger.info("Cancelled Stripe subscription for user: {}", user.getEmail());
                }
            }

            case "invoice.payment_failed" -> {
                String subId = root.path("data").path("object").path("subscription").asText("");
                Optional<Subscription> subOpt = subscriptionRepository.findByExternalSubscriptionId(subId);
                if (subOpt.isPresent()) {
                    subscription = subOpt.get();
                    user = subscription.getUser();
                    subscription.setStatus("PAST_DUE");
                    subscriptionRepository.save(subscription);
                    logger.warn("Stripe subscription payment failed for user: {}", user.getEmail());
                }
                txStatus = "FAILED";
            }

            case "charge.refunded" -> {
                String subId = root.path("data").path("object").path("subscription").asText("");
                Optional<Subscription> subOpt = subscriptionRepository.findByExternalSubscriptionId(subId);
                if (subOpt.isPresent()) {
                    subscription = subOpt.get();
                    user = subscription.getUser();
                    subscription.setStatus("REVOKED");
                    subscription.setCancelledAt(Instant.now());
                    subscription.setCurrentPeriodEnd(Instant.now());
                    subscriptionRepository.save(subscription);
                    logger.info("Revoked refunded Stripe subscription for user: {}", user.getEmail());
                }
                txStatus = "REFUNDED";
            }
        }

        PaymentTransaction tx = new PaymentTransaction(
                user, subscription, "STRIPE", eventId, eventType, amount, currency, txStatus, rawPayload
        );
        paymentTransactionRepository.save(tx);
        return true;
    }

    private SubscriptionTier parseTier(String plan) {
        if (plan == null) return SubscriptionTier.PRO_YEARLY;
        String p = plan.toLowerCase();
        if (p.contains("month")) return SubscriptionTier.PRO_MONTHLY;
        if (p.contains("life")) return SubscriptionTier.LIFETIME;
        return SubscriptionTier.PRO_YEARLY;
    }
}
