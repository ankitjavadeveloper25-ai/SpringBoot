package com.winterarc.server;

import com.winterarc.server.model.*;
import com.winterarc.server.repository.PaymentOrderRepository;
import com.winterarc.server.repository.PaymentTransactionRepository;
import com.winterarc.server.repository.SubscriptionRepository;
import com.winterarc.server.repository.UserRepository;
import com.winterarc.server.service.EntitlementSigningService;
import com.winterarc.server.service.PaymentWebhookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class WebhookAndEntitlementServerTest {

    @Autowired
    private EntitlementSigningService signingService;

    @Autowired
    private PaymentWebhookService webhookService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    @Autowired
    private PaymentOrderRepository paymentOrderRepository;

    @BeforeEach
    void cleanDb() {
        paymentTransactionRepository.deleteAll();
        paymentOrderRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void testEntitlementSigningAndVerification() {
        User user = new User("developer@winterarc.pro", "hashed_pwd", "Lead Developer");
        user = userRepository.save(user);

        Instant now = Instant.now();
        Instant oneYearLater = now.plusSeconds(365L * 86400L);
        Subscription sub = new Subscription(user, SubscriptionTier.PRO_YEARLY, "ACTIVE", "STRIPE", now, oneYearLater);
        sub = subscriptionRepository.save(sub);

        // Generate signed entitlement
        EntitlementToken entitlement = signingService.generateEntitlement(user, sub);

        assertNotNull(entitlement.getSignature());
        assertEquals(user.getId(), entitlement.getUserId());
        assertEquals(user.getEmail(), entitlement.getEmail());
        assertEquals(SubscriptionTier.PRO_YEARLY, entitlement.getTier());
        assertEquals(1209600L, entitlement.getOfflineGracePeriodSeconds()); // 14 days

        // Verify valid signature
        assertTrue(signingService.verifySignature(entitlement));

        // Test tampering detection
        EntitlementToken tampered = new EntitlementToken(
            entitlement.getSchemaVersion(),
            entitlement.getProductId(),
            entitlement.getTokenId(),
            entitlement.getKeyId(),
            entitlement.getUserId(),
            entitlement.getEmail(),
            SubscriptionTier.LIFETIME, // Tampered tier without valid signature
            entitlement.getIssuedAt(),
            entitlement.getExpiresAt(),
            entitlement.getOfflineGracePeriodSeconds(),
            entitlement.getSignature()
        );
        assertFalse(signingService.verifySignature(tampered));
    }

    @Test
    void testWebhookSignatureVerification() throws Exception {
        // 1. Stripe signature check
        String stripeSecret = "whsec_stripe_winterarc_prod_secret_key_test_123";
        String payload = "{\"id\":\"evt_test_123\",\"type\":\"checkout.session.completed\"}";
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000L);

        String signedPayload = timestamp + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(stripeSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sigHex = HexFormat.of().formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));

        String validHeader = "t=" + timestamp + ",v1=" + sigHex;
        assertTrue(webhookService.verifyStripeSignature(payload, validHeader));

        String invalidHeader = "t=" + timestamp + ",v1=invalid_fake_signature_hex_12345";
        assertFalse(webhookService.verifyStripeSignature(payload, invalidHeader));

        // 2. Razorpay signature check
        String razorpaySecret = "rzp_sec_razorpay_winterarc_prod_secret_key_test_123";
        String rzpPayload = "{\"entity\":\"event\",\"event\":\"payment.captured\"}";
        Mac rzpMac = Mac.getInstance("HmacSHA256");
        rzpMac.init(new SecretKeySpec(razorpaySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String rzpSigHex = HexFormat.of().formatHex(rzpMac.doFinal(rzpPayload.getBytes(StandardCharsets.UTF_8)));

        assertTrue(webhookService.verifyRazorpaySignature(rzpPayload, rzpSigHex));
        assertFalse(webhookService.verifyRazorpaySignature(rzpPayload, "bad_razorpay_signature"));
    }

    @Test
    void testStripeCheckoutSessionAndIdempotency() {
        String eventId = "evt_stripe_payment_success_001";
        String payload = """
            {
                "id": "evt_stripe_payment_success_001",
                "type": "checkout.session.completed",
                "data": {
                    "object": {
                        "customer_email": "subscriber@domain.com",
                        "amount_total": 399900,
                        "currency": "inr",
                        "subscription": "sub_ext_12345",
                        "metadata": {
                            "plan": "pro_yearly"
                        }
                    }
                }
            }
        """;

        // 1. Process event first time
        boolean processedFirst = webhookService.processWebhookEvent("STRIPE", eventId, "checkout.session.completed", payload);
        assertTrue(processedFirst);

        // Verify user and subscription were created
        Optional<User> userOpt = userRepository.findByEmail("subscriber@domain.com");
        assertTrue(userOpt.isPresent());
        User user = userOpt.get();

        Optional<Subscription> subOpt = subscriptionRepository.findTopByUserOrderByCreatedAtDesc(user);
        assertTrue(subOpt.isPresent());
        assertEquals(SubscriptionTier.PRO_YEARLY, subOpt.get().getTier());
        assertEquals("ACTIVE", subOpt.get().getStatus());

        // 2. Idempotency test: Re-process the exact same event
        boolean processedSecond = webhookService.processWebhookEvent("STRIPE", eventId, "checkout.session.completed", payload);
        assertTrue(processedSecond);

        // Verify that duplicate subscriptions were NOT created
        assertEquals(1, subscriptionRepository.findByUser(user).size());
        assertEquals(1, paymentTransactionRepository.count());
    }

    @Test
    void testStripeRenewalAndPaymentFailed() {
        User user = userRepository.save(new User("renew@winterarc.com", "hash", "Renew User"));
        Instant now = Instant.now();
        Instant end = now.plusSeconds(30L * 86400L);
        Subscription sub = new Subscription(user, SubscriptionTier.PRO_MONTHLY, "ACTIVE", "STRIPE", now, end);
        sub.setExternalSubscriptionId("sub_renew_001");
        subscriptionRepository.save(sub);

        // 1. Renewal Webhook (invoice.payment_succeeded)
        String renewPayload = """
            {
                "id": "evt_renew_001",
                "type": "invoice.payment_succeeded",
                "data": {
                    "object": {
                        "subscription": "sub_renew_001"
                    }
                }
            }
        """;
        assertTrue(webhookService.processWebhookEvent("STRIPE", "evt_renew_001", "invoice.payment_succeeded", renewPayload));
        Subscription renewed = subscriptionRepository.findByExternalSubscriptionId("sub_renew_001").orElseThrow();
        assertEquals("ACTIVE", renewed.getStatus());
        assertTrue(renewed.getCurrentPeriodEnd().isAfter(end));

        // 2. Payment Failure Webhook (invoice.payment_failed)
        String failPayload = """
            {
                "id": "evt_fail_001",
                "type": "invoice.payment_failed",
                "data": {
                    "object": {
                        "subscription": "sub_renew_001"
                    }
                }
            }
        """;
        assertTrue(webhookService.processWebhookEvent("STRIPE", "evt_fail_001", "invoice.payment_failed", failPayload));
        Subscription failed = subscriptionRepository.findByExternalSubscriptionId("sub_renew_001").orElseThrow();
        assertEquals("PAST_DUE", failed.getStatus());
    }

    @Test
    void testSubscriptionCancellationAndRefundRevocation() {
        // Setup user with subscription
        User user = userRepository.save(new User("refund_target@winterarc.com", "hash", "Refund User"));
        Subscription sub = new Subscription(user, SubscriptionTier.PRO_YEARLY, "ACTIVE", "STRIPE",
            Instant.now(), Instant.now().plusSeconds(365L * 86400L));
        sub.setExternalSubscriptionId("sub_refund_999");
        subscriptionRepository.save(sub);

        // 1. Process Refund Webhook (charge.refunded)
        String refundPayload = """
            {
                "id": "evt_refund_999",
                "type": "charge.refunded",
                "data": {
                    "object": {
                        "subscription": "sub_refund_999"
                    }
                }
            }
        """;
        assertTrue(webhookService.processWebhookEvent("STRIPE", "evt_refund_999", "charge.refunded", refundPayload));

        Subscription refunded = subscriptionRepository.findByExternalSubscriptionId("sub_refund_999").orElseThrow();
        assertEquals("REVOKED", refunded.getStatus());
        assertFalse(refunded.isActive(), "Refunded subscription must not be active");

        // Server entitlement must evaluate to FREE
        EntitlementToken token = signingService.generateEntitlement(user, refunded);
        assertEquals(SubscriptionTier.FREE, token.getTier(), "User with refunded subscription must receive FREE entitlement");
    }

    @Test
    void testRazorpayPaymentCapturedAndRefund() {
        // Pre-create user and pending PaymentOrder as required by secure webhook processing
        User seedUser = userRepository.save(new User("indian_coder@winterarc.in", "hash", "Indian Coder"));
        PaymentOrder seedOrder = new PaymentOrder(seedUser, "sub_rzp_999", 399900L, "INR", SubscriptionTier.PRO_YEARLY, "rcpt_test_999");
        paymentOrderRepository.save(seedOrder);

        String rzpPayload = """
            {
                "entity": "event",
                "event": "payment.captured",
                "payload": {
                    "payment": {
                        "entity": {
                            "id": "pay_rzp_test_001",
                            "email": "indian_coder@winterarc.in",
                            "amount": 399900,
                            "currency": "INR",
                            "order_id": "sub_rzp_999",
                            "subscription_id": "sub_rzp_999",
                            "notes": {
                                "plan": "pro_yearly"
                            }
                        }
                    }
                }
            }
        """;

        assertTrue(webhookService.processWebhookEvent("RAZORPAY", "rzp_evt_001", "payment.captured", rzpPayload));

        User user = userRepository.findByEmail("indian_coder@winterarc.in").orElseThrow();
        Subscription sub = subscriptionRepository.findByExternalSubscriptionId("sub_rzp_999").orElseThrow();
        assertEquals(SubscriptionTier.PRO_YEARLY, sub.getTier());
        assertEquals("ACTIVE", sub.getStatus());

        // Test Razorpay Refund
        String rzpRefundPayload = """
            {
                "entity": "event",
                "event": "refund.processed",
                "payload": {
                    "refund": {
                        "entity": {
                            "id": "rfnd_rzp_001",
                            "payment_id": "sub_rzp_999"
                        }
                    }
                }
            }
        """;
        assertTrue(webhookService.processWebhookEvent("RAZORPAY", "rzp_evt_002", "refund.processed", rzpRefundPayload));

        Subscription refundedSub = subscriptionRepository.findByExternalSubscriptionId("sub_rzp_999").orElseThrow();
        assertEquals("REVOKED", refundedSub.getStatus());
    }
}
