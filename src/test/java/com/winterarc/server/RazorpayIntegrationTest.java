package com.winterarc.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.winterarc.server.config.JwtUtil;
import com.winterarc.server.controller.CheckoutController;
import com.winterarc.server.dto.RazorpayOrderResponse;
import com.winterarc.server.dto.RazorpayPaymentResponse;
import com.winterarc.server.exception.RazorpayApiException;
import com.winterarc.server.model.*;
import com.winterarc.server.repository.*;
import com.winterarc.server.service.AdminSubscriptionService;
import com.winterarc.server.service.EntitlementSigningService;
import com.winterarc.server.service.PaymentWebhookService;
import com.winterarc.server.service.RazorpayGateway;
import com.winterarc.server.service.RazorpayPaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
public class RazorpayIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private PaymentOrderRepository paymentOrderRepository;

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    @Autowired
    private SubscriptionGrantRepository grantRepository;

    @Autowired
    private AdminSubscriptionService adminSubscriptionService;

    @Autowired
    private RazorpayPaymentService razorpayPaymentService;

    @Autowired
    private PaymentWebhookService paymentWebhookService;

    @Autowired
    private EntitlementSigningService signingService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @MockBean
    private RazorpayGateway razorpayGateway;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User primaryUser;
    private User secondaryUser;
    private String primaryUserToken;
    private String secondaryUserToken;

    @BeforeEach
    void setUp() {
        paymentTransactionRepository.deleteAll();
        grantRepository.deleteAll();
        paymentOrderRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();

        primaryUser = new User("alice@winterarc.pro", passwordEncoder.encode("SecretPass123!"), "Alice Developer");
        primaryUser = userRepository.save(primaryUser);

        secondaryUser = new User("bob@winterarc.pro", passwordEncoder.encode("SecretPass123!"), "Bob Developer");
        secondaryUser = userRepository.save(secondaryUser);

        primaryUserToken = jwtUtil.generateToken(primaryUser.getId(), primaryUser.getEmail(), "ROLE_USER");
        secondaryUserToken = jwtUtil.generateToken(secondaryUser.getId(), secondaryUser.getEmail(), "ROLE_USER");

        when(razorpayGateway.getKeyId()).thenReturn("rzp_test_mock_key_id");
    }

    // --------------------------------------------------------------------------------------------------
    // 1. Successful order creation
    // --------------------------------------------------------------------------------------------------
    @Test
    @DisplayName("Test 1: Successful Razorpay order creation persists internal pending PaymentOrder")
    void test01_SuccessfulOrderCreation() throws Exception {
        when(razorpayGateway.createOrder(eq(399900L), eq("INR"), anyString(), any()))
                .thenReturn(new RazorpayOrderResponse("order_rzp_test_001", "INR", 399900L, "created", "rcpt_001"));

        CheckoutController.CheckoutRequest req = new CheckoutController.CheckoutRequest("PRO_YEARLY", "RAZORPAY", "INR", "idem_key_001");

        mockMvc.perform(post("/api/checkout/razorpay/create-order")
                        .header("Authorization", "Bearer " + primaryUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("order_rzp_test_001"))
                .andExpect(jsonPath("$.amount").value(399900))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.plan").value("PRO_YEARLY"))
                .andExpect(jsonPath("$.keyId").isNotEmpty())
                .andExpect(jsonPath("$.checkoutUrl").isNotEmpty());

        Optional<PaymentOrder> savedOrder = paymentOrderRepository.findByRazorpayOrderId("order_rzp_test_001");
        assertTrue(savedOrder.isPresent());
        PaymentOrder order = savedOrder.get();
        assertEquals(primaryUser.getId(), order.getUser().getId());
        assertEquals(SubscriptionTier.PRO_YEARLY, order.getPlan());
        assertEquals(399900L, order.getAmount());
        assertEquals("INR", order.getCurrency());
        assertEquals("PENDING", order.getStatus());
        assertEquals("idem_key_001", order.getIdempotencyKey());
    }

    // --------------------------------------------------------------------------------------------------
    // 2. Invalid plan and invalid currency
    // --------------------------------------------------------------------------------------------------
    @Test
    @DisplayName("Test 2: Rejection of invalid plans, invalid currencies, and unsupported providers")
    void test02_InvalidPlanAndInvalidCurrency() throws Exception {
        // A. Invalid plan name
        CheckoutController.CheckoutRequest invalidPlanReq =
                new CheckoutController.CheckoutRequest("NON_EXISTENT_PLAN", "RAZORPAY", "INR", null);

        mockMvc.perform(post("/api/checkout/razorpay/create-order")
                        .header("Authorization", "Bearer " + primaryUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidPlanReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Invalid subscription plan")));

        // B. FREE tier cannot be purchased
        CheckoutController.CheckoutRequest freePlanReq =
                new CheckoutController.CheckoutRequest("FREE", "RAZORPAY", "INR", null);

        mockMvc.perform(post("/api/checkout/razorpay/create-order")
                        .header("Authorization", "Bearer " + primaryUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(freePlanReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("FREE tier cannot be purchased")));

        // C. Unsupported currency for Razorpay (only INR supported)
        CheckoutController.CheckoutRequest invalidCurrencyReq =
                new CheckoutController.CheckoutRequest("PRO_YEARLY", "RAZORPAY", "EUR", null);

        mockMvc.perform(post("/api/checkout/razorpay/create-order")
                        .header("Authorization", "Bearer " + primaryUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidCurrencyReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Unsupported currency")));

        // D. Unsupported provider on unified checkout endpoint
        CheckoutController.CheckoutRequest unsupportedProviderReq =
                new CheckoutController.CheckoutRequest("PRO_YEARLY", "BITCOIN", "INR", null);

        mockMvc.perform(post("/api/checkout/create-session")
                        .header("Authorization", "Bearer " + primaryUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(unsupportedProviderReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Unsupported payment provider")));
    }

    // --------------------------------------------------------------------------------------------------
    // 3. Invalid payment signatures
    // --------------------------------------------------------------------------------------------------
    @Test
    @DisplayName("Test 3: Reject payment verification when signature is invalid")
    void test03_InvalidPaymentSignatures() throws Exception {
        PaymentOrder order = new PaymentOrder(primaryUser, "order_rzp_sig_fail_001", 399900L, "INR",
                SubscriptionTier.PRO_YEARLY, "rcpt_sig_fail");
        paymentOrderRepository.save(order);

        when(razorpayGateway.verifyPaymentSignature(eq("order_rzp_sig_fail_001"), eq("pay_fake_001"), eq("bad_signature")))
                .thenReturn(false);

        CheckoutController.RazorpayVerifyRequest verifyReq =
                new CheckoutController.RazorpayVerifyRequest("order_rzp_sig_fail_001", "pay_fake_001", "bad_signature");

        mockMvc.perform(post("/api/checkout/razorpay/verify")
                        .header("Authorization", "Bearer " + primaryUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(verifyReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Invalid payment signature")));

        // Order must not be marked PAID and no subscription granted
        PaymentOrder unchangedOrder = paymentOrderRepository.findByRazorpayOrderId("order_rzp_sig_fail_001").orElseThrow();
        assertEquals("PENDING", unchangedOrder.getStatus());
        assertTrue(subscriptionRepository.findByUser(primaryUser).isEmpty());
    }

    // --------------------------------------------------------------------------------------------------
    // 4. Correct signature but wrong order ID
    // --------------------------------------------------------------------------------------------------
    @Test
    @DisplayName("Test 4: Correct signature format but order ID does not exist in database")
    void test04_CorrectSignatureWrongOrderId() throws Exception {
        CheckoutController.RazorpayVerifyRequest verifyReq =
                new CheckoutController.RazorpayVerifyRequest("order_rzp_nonexistent_999", "pay_dummy_001", "valid_looking_sig");

        mockMvc.perform(post("/api/checkout/razorpay/verify")
                        .header("Authorization", "Bearer " + primaryUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(verifyReq)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("No order found with razorpayOrderId")));

        assertTrue(subscriptionRepository.findByUser(primaryUser).isEmpty());
    }

    // --------------------------------------------------------------------------------------------------
    // 5. Amount and currency mismatches
    // --------------------------------------------------------------------------------------------------
    @Test
    @DisplayName("Test 5: Reject payment verification if upstream amount or currency does not match internal order")
    void test05_AmountAndCurrencyMismatches() throws Exception {
        PaymentOrder order = new PaymentOrder(primaryUser, "order_rzp_amt_mismatch_001", 399900L, "INR",
                SubscriptionTier.PRO_YEARLY, "rcpt_mismatch");
        paymentOrderRepository.save(order);

        when(razorpayGateway.verifyPaymentSignature(anyString(), anyString(), anyString())).thenReturn(true);

        // A. Upstream payment amount mismatch (e.g. user tampered or paid lower amount)
        when(razorpayGateway.fetchPayment("pay_mismatch_001"))
                .thenReturn(new RazorpayPaymentResponse("pay_mismatch_001", "order_rzp_amt_mismatch_001", 100000L, "INR", "captured", true));

        CheckoutController.RazorpayVerifyRequest req1 =
                new CheckoutController.RazorpayVerifyRequest("order_rzp_amt_mismatch_001", "pay_mismatch_001", "valid_sig");

        mockMvc.perform(post("/api/checkout/razorpay/verify")
                        .header("Authorization", "Bearer " + primaryUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Payment amount mismatch")));

        // B. Upstream currency mismatch
        when(razorpayGateway.fetchPayment("pay_currency_mismatch_002"))
                .thenReturn(new RazorpayPaymentResponse("pay_currency_mismatch_002", "order_rzp_amt_mismatch_001", 399900L, "USD", "captured", true));

        CheckoutController.RazorpayVerifyRequest req2 =
                new CheckoutController.RazorpayVerifyRequest("order_rzp_amt_mismatch_001", "pay_currency_mismatch_002", "valid_sig");

        mockMvc.perform(post("/api/checkout/razorpay/verify")
                        .header("Authorization", "Bearer " + primaryUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Payment currency mismatch")));

        assertTrue(subscriptionRepository.findByUser(primaryUser).isEmpty());
    }

    // --------------------------------------------------------------------------------------------------
    // 6. Payment belonging to a different user
    // --------------------------------------------------------------------------------------------------
    @Test
    @DisplayName("Test 6: Reject verification when authenticated user does not own the order")
    void test06_PaymentBelongingToDifferentUser() throws Exception {
        // Order belongs to primaryUser (Alice)
        PaymentOrder order = new PaymentOrder(primaryUser, "order_rzp_alice_001", 399900L, "INR",
                SubscriptionTier.PRO_YEARLY, "rcpt_alice");
        paymentOrderRepository.save(order);

        // SecondaryUser (Bob) attempts to verify Alice's order
        CheckoutController.RazorpayVerifyRequest req =
                new CheckoutController.RazorpayVerifyRequest("order_rzp_alice_001", "pay_alice_001", "sig_alice");

        mockMvc.perform(post("/api/checkout/razorpay/verify")
                        .header("Authorization", "Bearer " + secondaryUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("does not belong to the authenticated user")));

        assertTrue(subscriptionRepository.findByUser(secondaryUser).isEmpty());
        assertTrue(subscriptionRepository.findByUser(primaryUser).isEmpty());
    }

    // --------------------------------------------------------------------------------------------------
    // 7. Uncaptured payment
    // --------------------------------------------------------------------------------------------------
    @Test
    @DisplayName("Test 7: Reject verification when payment status is not captured (e.g. authorized only)")
    void test07_UncapturedPayment() throws Exception {
        PaymentOrder order = new PaymentOrder(primaryUser, "order_rzp_uncaptured_001", 399900L, "INR",
                SubscriptionTier.PRO_YEARLY, "rcpt_uncaptured");
        paymentOrderRepository.save(order);

        when(razorpayGateway.verifyPaymentSignature(anyString(), anyString(), anyString())).thenReturn(true);
        when(razorpayGateway.fetchPayment("pay_authorized_only_001"))
                .thenReturn(new RazorpayPaymentResponse("pay_authorized_only_001", "order_rzp_uncaptured_001", 399900L, "INR", "authorized", false));

        CheckoutController.RazorpayVerifyRequest req =
                new CheckoutController.RazorpayVerifyRequest("order_rzp_uncaptured_001", "pay_authorized_only_001", "sig_valid");

        mockMvc.perform(post("/api/checkout/razorpay/verify")
                        .header("Authorization", "Bearer " + primaryUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Payment is not captured")));

        PaymentOrder unchanged = paymentOrderRepository.findByRazorpayOrderId("order_rzp_uncaptured_001").orElseThrow();
        assertEquals("PENDING", unchanged.getStatus());
        assertTrue(subscriptionRepository.findByUser(primaryUser).isEmpty());
    }

    // --------------------------------------------------------------------------------------------------
    // 8. Successful payment verification
    // --------------------------------------------------------------------------------------------------
    @Test
    @DisplayName("Test 8: Successful payment verification activates Pro subscription, issues signed token, records transaction")
    void test08_SuccessfulPaymentVerification() throws Exception {
        PaymentOrder order = new PaymentOrder(primaryUser, "order_rzp_success_001", 399900L, "INR",
                SubscriptionTier.PRO_YEARLY, "rcpt_success");
        paymentOrderRepository.save(order);

        when(razorpayGateway.verifyPaymentSignature(eq("order_rzp_success_001"), eq("pay_rzp_success_001"), eq("valid_sig_123")))
                .thenReturn(true);
        when(razorpayGateway.fetchPayment("pay_rzp_success_001"))
                .thenReturn(new RazorpayPaymentResponse("pay_rzp_success_001", "order_rzp_success_001", 399900L, "INR", "captured", true));

        CheckoutController.RazorpayVerifyRequest req =
                new CheckoutController.RazorpayVerifyRequest("order_rzp_success_001", "pay_rzp_success_001", "valid_sig_123");

        mockMvc.perform(post("/api/checkout/razorpay/verify")
                        .header("Authorization", "Bearer " + primaryUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.tier").value("PRO_YEARLY"))
                .andExpect(jsonPath("$.token.signature").isNotEmpty());

        // Verify DB updates
        PaymentOrder updatedOrder = paymentOrderRepository.findByRazorpayOrderId("order_rzp_success_001").orElseThrow();
        assertEquals("PAID", updatedOrder.getStatus());
        assertNotNull(updatedOrder.getPaidAt());
        assertEquals("pay_rzp_success_001", updatedOrder.getRazorpayPaymentId());

        Subscription sub = subscriptionRepository.findTopByUserOrderByCreatedAtDesc(primaryUser).orElseThrow();
        assertEquals(SubscriptionTier.PRO_YEARLY, sub.getTier());
        assertEquals("ACTIVE", sub.getStatus());
        assertEquals("RAZORPAY", sub.getPaymentProvider());

        Optional<PaymentTransaction> txOpt = paymentTransactionRepository.findByPaymentId("pay_rzp_success_001");
        assertTrue(txOpt.isPresent());
        assertEquals("SUCCEEDED", txOpt.get().getStatus());
    }

    // --------------------------------------------------------------------------------------------------
    // 9. Duplicate webhook delivery
    // --------------------------------------------------------------------------------------------------
    @Test
    @DisplayName("Test 9: Webhook deduplication ensures identical event ID is processed idempotently")
    void test09_DuplicateWebhookDelivery() {
        PaymentOrder order = new PaymentOrder(primaryUser, "order_rzp_dup_001", 399900L, "INR",
                SubscriptionTier.PRO_YEARLY, "rcpt_dup");
        paymentOrderRepository.save(order);

        String eventId = "rzp_evt_dup_test_001";
        String webhookPayload = """
            {
                "entity": "event",
                "event": "payment.captured",
                "payload": {
                    "payment": {
                        "entity": {
                            "id": "pay_dup_001",
                            "order_id": "order_rzp_dup_001",
                            "amount": 399900,
                            "currency": "INR",
                            "notes": {}
                        }
                    }
                }
            }
        """;

        // First delivery
        boolean firstProcessed = paymentWebhookService.processWebhookEvent("RAZORPAY", eventId, "payment.captured", webhookPayload);
        assertTrue(firstProcessed);
        assertEquals(1, subscriptionRepository.findByUser(primaryUser).size());
        assertTrue(paymentTransactionRepository.findByOrderId("order_rzp_dup_001").isPresent());

        // Second duplicate delivery with exact same event ID
        boolean secondProcessed = paymentWebhookService.processWebhookEvent("RAZORPAY", eventId, "payment.captured", webhookPayload);
        assertTrue(secondProcessed);

        // Verification: No duplicate subscriptions or duplicate transactions
        assertEquals(1, subscriptionRepository.findByUser(primaryUser).size());
        assertTrue(paymentTransactionRepository.findByOrderId("order_rzp_dup_001").isPresent());
    }

    // --------------------------------------------------------------------------------------------------
    // 10. Out-of-order webhook events
    // --------------------------------------------------------------------------------------------------
    @Test
    @DisplayName("Test 10: Delayed payment.failed webhook does not downgrade or revoke an already paid order")
    void test10_OutOfOrderWebhookEvents() {
        PaymentOrder order = new PaymentOrder(primaryUser, "order_rzp_ooo_001", 399900L, "INR",
                SubscriptionTier.PRO_YEARLY, "rcpt_ooo");
        order.setStatus("PAID");
        order.setRazorpayPaymentId("pay_ooo_captured_001");
        paymentOrderRepository.save(order);

        // User already has an active subscription
        Instant now = Instant.now();
        Subscription activeSub = new Subscription(primaryUser, SubscriptionTier.PRO_YEARLY, "ACTIVE", "RAZORPAY", now, now.plusSeconds(365 * 86400L));
        activeSub.setExternalSubscriptionId("order_rzp_ooo_001");
        subscriptionRepository.save(activeSub);

        // Delayed out-of-order failure webhook arrives
        String delayedFailurePayload = """
            {
                "entity": "event",
                "event": "payment.failed",
                "payload": {
                    "payment": {
                        "entity": {
                            "id": "pay_ooo_failed_attempt_002",
                            "order_id": "order_rzp_ooo_001",
                            "amount": 399900,
                            "currency": "INR"
                        }
                    }
                }
            }
        """;

        boolean handled = paymentWebhookService.processWebhookEvent("RAZORPAY", "rzp_evt_delayed_fail", "payment.failed", delayedFailurePayload);
        assertTrue(handled);

        // Ensure order remains PAID and subscription remains ACTIVE
        PaymentOrder checkOrder = paymentOrderRepository.findByRazorpayOrderId("order_rzp_ooo_001").orElseThrow();
        assertEquals("PAID", checkOrder.getStatus());

        Subscription checkSub = subscriptionRepository.findTopByUserOrderByCreatedAtDesc(primaryUser).orElseThrow();
        assertEquals("ACTIVE", checkSub.getStatus());
        assertTrue(checkSub.isActive());
    }

    // --------------------------------------------------------------------------------------------------
    // 11. Missing or malformed webhook fields
    // --------------------------------------------------------------------------------------------------
    @Test
    @DisplayName("Test 11: Malformed JSON or missing required webhook fields handled gracefully without crashing")
    void test11_MissingOrMalformedWebhookFields() throws Exception {
        when(razorpayGateway.verifyWebhookSignature(anyString(), anyString())).thenReturn(true);

        // A. Malformed JSON
        mockMvc.perform(post("/api/webhooks/razorpay")
                        .header("X-Razorpay-Signature", "sig_val")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid_json: true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid JSON payload"));

        // B. Missing payload entity structure
        String emptyEntityPayload = """
            {
                "entity": "event",
                "event": "payment.captured",
                "payload": {}
            }
        """;
        mockMvc.perform(post("/api/webhooks/razorpay")
                        .header("X-Razorpay-Signature", "sig_val")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emptyEntityPayload))
                .andExpect(status().isOk()); // quarantined and acknowledged without crashing
    }

    // --------------------------------------------------------------------------------------------------
    // 12. Unknown order IDs
    // --------------------------------------------------------------------------------------------------
    @Test
    @DisplayName("Test 12: Unknown order IDs are quarantined without creating fake users or granting subscriptions")
    void test12_UnknownOrderIdsQuarantined() {
        String unknownPayload = """
            {
                "entity": "event",
                "event": "payment.captured",
                "payload": {
                    "payment": {
                        "entity": {
                            "id": "pay_unknown_order_001",
                            "order_id": "order_unknown_never_created_999",
                            "amount": 399900,
                            "currency": "INR",
                            "email": "attacker_supplied_email@evil.com"
                        }
                    }
                }
            }
        """;

        long userCountBefore = userRepository.count();
        long subCountBefore = subscriptionRepository.count();

        boolean processed = paymentWebhookService.processWebhookEvent("RAZORPAY", "rzp_evt_unknown_001", "payment.captured", unknownPayload);
        assertTrue(processed); // Acknowledged to provider

        // Strict security rule: Never create user from webhook data, never grant unlinked subscription
        assertEquals(userCountBefore, userRepository.count());
        assertEquals(subCountBefore, subscriptionRepository.count());
        assertTrue(userRepository.findByEmail("attacker_supplied_email@evil.com").isEmpty());

        // Event quarantined as UNMATCHED_ORDER in audit transactions
        Optional<PaymentTransaction> auditTx = paymentTransactionRepository.findByEventId("rzp_evt_unknown_001");
        assertTrue(auditTx.isPresent());
        assertEquals("UNMATCHED_ORDER", auditTx.get().getStatus());
    }

    // --------------------------------------------------------------------------------------------------
    // 13. Transient database/provider failures
    // --------------------------------------------------------------------------------------------------
    @Test
    @DisplayName("Test 13: Gateway timeout and upstream provider errors mapped to HTTP status codes cleanly")
    void test13_TransientProviderFailures() throws Exception {
        when(razorpayGateway.createOrder(anyLong(), anyString(), anyString(), any()))
                .thenThrow(new RazorpayApiException(504, "GATEWAY_TIMEOUT", "Connection to Razorpay timed out"));

        CheckoutController.CheckoutRequest req = new CheckoutController.CheckoutRequest("PRO_YEARLY", "RAZORPAY", "INR", null);

        mockMvc.perform(post("/api/checkout/razorpay/create-order")
                        .header("Authorization", "Bearer " + primaryUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().is(504))
                .andExpect(jsonPath("$.code").value("GATEWAY_TIMEOUT"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("timed out")));

        assertTrue(paymentOrderRepository.findAll().isEmpty());
    }

    // --------------------------------------------------------------------------------------------------
    // 14. Idempotent subscription activation
    // --------------------------------------------------------------------------------------------------
    @Test
    @DisplayName("Test 14: Repeated payment verification requests do not create duplicate subscriptions or double-extend expiry")
    void test14_IdempotentSubscriptionActivation() throws Exception {
        PaymentOrder order = new PaymentOrder(primaryUser, "order_rzp_idem_001", 399900L, "INR",
                SubscriptionTier.PRO_YEARLY, "rcpt_idem");
        paymentOrderRepository.save(order);

        when(razorpayGateway.verifyPaymentSignature(anyString(), anyString(), anyString())).thenReturn(true);
        when(razorpayGateway.fetchPayment("pay_idem_001"))
                .thenReturn(new RazorpayPaymentResponse("pay_idem_001", "order_rzp_idem_001", 399900L, "INR", "captured", true));

        CheckoutController.RazorpayVerifyRequest verifyReq =
                new CheckoutController.RazorpayVerifyRequest("order_rzp_idem_001", "pay_idem_001", "sig_idem");

        // 1st Call: First verification
        mockMvc.perform(post("/api/checkout/razorpay/verify")
                        .header("Authorization", "Bearer " + primaryUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(verifyReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        Subscription firstSub = subscriptionRepository.findTopByUserOrderByCreatedAtDesc(primaryUser).orElseThrow();
        Instant initialExpiry = firstSub.getCurrentPeriodEnd();

        // 2nd Call: Retry / re-verification of already paid order
        mockMvc.perform(post("/api/checkout/razorpay/verify")
                        .header("Authorization", "Bearer " + primaryUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(verifyReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ALREADY_ACTIVE"));

        // Verify count remains 1 and expiry was NOT double-extended
        assertEquals(1, subscriptionRepository.findByUser(primaryUser).size());
        Subscription secondSub = subscriptionRepository.findTopByUserOrderByCreatedAtDesc(primaryUser).orElseThrow();
        assertEquals(initialExpiry, secondSub.getCurrentPeriodEnd());
    }

    // --------------------------------------------------------------------------------------------------
    // 15. Entitlement signing compatibility
    // --------------------------------------------------------------------------------------------------
    @Test
    @DisplayName("Test 15: Entitlement tokens produced for Razorpay subscribers verify under Ed25519 cryptography")
    void test15_EntitlementSigningCompatibility() {
        PaymentOrder order = new PaymentOrder(primaryUser, "order_rzp_ent_001", 399900L, "INR",
                SubscriptionTier.PRO_YEARLY, "rcpt_ent");
        order.setStatus("PAID");
        paymentOrderRepository.save(order);

        Subscription sub = razorpayPaymentService.activateSubscriptionForOrder(order, "pay_ent_001", "TEST");

        EntitlementToken token = signingService.generateEntitlement(primaryUser, sub);

        assertNotNull(token);
        assertEquals(primaryUser.getId(), token.getUserId());
        assertEquals(primaryUser.getEmail(), token.getEmail());
        assertEquals(SubscriptionTier.PRO_YEARLY, token.getTier());
        assertEquals("ed25519-2026-v1", token.getKeyId());
        assertEquals("winter-arc-desktop", token.getProductId());
        assertEquals(1209600L, token.getOfflineGracePeriodSeconds()); // 14 days offline grace

        assertTrue(signingService.verifySignature(token), "Ed25519 cryptographic signature must verify successfully");
    }

    // --------------------------------------------------------------------------------------------------
    // 16. Existing complimentary grants and paid subscription behavior
    // --------------------------------------------------------------------------------------------------
    @Test
    @DisplayName("Test 16: Paid Razorpay subscriptions take precedence over complimentary grants without erasing paid time")
    void test16_ExistingComplimentaryGrantsAndPaidSubscription() {
        // Step 1: Admin grants complimentary access to primaryUser
        AdminSubscriptionService.GrantRequest grantReq = new AdminSubscriptionService.GrantRequest(
                SubscriptionTier.PRO_MONTHLY, 30, null, "SUPPORT", "Early adopter beta access");

        adminSubscriptionService.grantComplimentarySubscription(
                "admin_id_001", "admin@winterarc.com", primaryUser.getId(), grantReq, "127.0.0.1");

        Subscription compSub = subscriptionRepository.findTopByUserOrderByCreatedAtDesc(primaryUser).orElseThrow();
        assertEquals(SubscriptionTier.PRO_MONTHLY, compSub.getTier());
        assertEquals("COMPLIMENTARY", compSub.getPaymentProvider());

        // Step 2: User purchases paid PRO_YEARLY through Razorpay
        PaymentOrder paidOrder = new PaymentOrder(primaryUser, "order_rzp_comp_upgrade_001", 399900L, "INR",
                SubscriptionTier.PRO_YEARLY, "rcpt_comp_upgrade");
        paidOrder.setStatus("PAID");
        paymentOrderRepository.save(paidOrder);

        Subscription upgradedSub = razorpayPaymentService.activateSubscriptionForOrder(paidOrder, "pay_comp_upgrade_001", "RAZORPAY_CHECKOUT");

        // Paid subscription authoritative precedence: provider updated to RAZORPAY and tier to PRO_YEARLY
        assertEquals("RAZORPAY", upgradedSub.getPaymentProvider());
        assertEquals(SubscriptionTier.PRO_YEARLY, upgradedSub.getTier());
        assertEquals("ACTIVE", upgradedSub.getStatus());

        // Step 3: Admin complimentary grant later issued to a paid subscriber extends from period end without downgrading
        Instant currentPaidEnd = upgradedSub.getCurrentPeriodEnd();
        AdminSubscriptionService.GrantRequest extraGrant = new AdminSubscriptionService.GrantRequest(
                SubscriptionTier.PRO_YEARLY, 30, null, "RETENTION", "Loyalty bonus");

        adminSubscriptionService.grantComplimentarySubscription(
                "admin_id_001", "admin@winterarc.com", primaryUser.getId(), extraGrant, "127.0.0.1");

        Subscription postBonusSub = subscriptionRepository.findTopByUserOrderByCreatedAtDesc(primaryUser).orElseThrow();
        assertTrue(postBonusSub.getCurrentPeriodEnd().isAfter(currentPaidEnd));
    }
}
