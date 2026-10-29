package com.winterarc.server.service;

import com.winterarc.server.dto.RazorpayCheckoutDto;
import com.winterarc.server.dto.RazorpayOrderResponse;
import com.winterarc.server.dto.RazorpayPaymentResponse;
import com.winterarc.server.exception.InvalidPaymentRequestException;
import com.winterarc.server.exception.OrderNotFoundException;
import com.winterarc.server.exception.PaymentVerificationException;
import com.winterarc.server.model.*;
import com.winterarc.server.repository.PaymentOrderRepository;
import com.winterarc.server.repository.PaymentTransactionRepository;
import com.winterarc.server.repository.SubscriptionRepository;
import com.winterarc.server.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class RazorpayPaymentService {

    private static final Logger logger = LoggerFactory.getLogger(RazorpayPaymentService.class);

    private final UserRepository userRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final RazorpayGateway razorpayGateway;
    private final EntitlementSigningService signingService;
    private final PlanCatalogService planCatalogService;

    public RazorpayPaymentService(
            UserRepository userRepository,
            PaymentOrderRepository paymentOrderRepository,
            SubscriptionRepository subscriptionRepository,
            PaymentTransactionRepository paymentTransactionRepository,
            RazorpayGateway razorpayGateway,
            EntitlementSigningService signingService,
            PlanCatalogService planCatalogService) {
        this.userRepository = userRepository;
        this.paymentOrderRepository = paymentOrderRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.razorpayGateway = razorpayGateway;
        this.signingService = signingService;
        this.planCatalogService = planCatalogService;
    }

    /**
     * Creates or retrieves an existing idempotent Razorpay order for an authenticated user.
     */
    @Transactional
    public RazorpayCheckoutDto createOrder(String userId, String planStr, String currencyStr, String idempotencyKey) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new InvalidPaymentRequestException("Authenticated user not found: " + userId));

        PlanCatalogService.ResolvedPlan resolvedPlan = planCatalogService.resolvePlan(planStr);
        SubscriptionTier tier = resolvedPlan.tier();
        String currency = validateAndNormalizeCurrency(currencyStr);

        // Derive trusted amount strictly on the server-side from authoritative catalog
        long amount = "USD".equalsIgnoreCase(currency) ? tier.getUsdCents() : resolvedPlan.amountPaise();

        // 1. Idempotency Check: if exact idempotency key was supplied and order is pending/paid, return it
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<PaymentOrder> existingOpt = paymentOrderRepository.findByIdempotencyKey(idempotencyKey.trim());
            if (existingOpt.isPresent()) {
                PaymentOrder existing = existingOpt.get();
                if (existing.getUser().getId().equals(user.getId()) && existing.getRazorpayOrderId() != null) {
                    logger.info("Reusing existing order {} for idempotencyKey {}", existing.getRazorpayOrderId(), idempotencyKey);
                    return mapToCheckoutDto(existing, user);
                }
            }
        }

        // 2. Prevent accidental duplicate order creation when request is retried rapidly without idempotencyKey
        Instant recentWindow = Instant.now().minus(Duration.ofMinutes(10));
        Optional<PaymentOrder> recentPendingOpt = paymentOrderRepository
                .findFirstByUserIdAndPlanAndCurrencyAndStatusOrderByCreatedAtDesc(user.getId(), tier, currency, "PENDING");
        if (recentPendingOpt.isPresent()) {
            PaymentOrder recent = recentPendingOpt.get();
            if (recent.getCreatedAt().isAfter(recentWindow) && recent.getRazorpayOrderId() != null) {
                logger.info("Found active recent pending order {} for user {} - returning existing order.", recent.getRazorpayOrderId(), user.getEmail());
                return mapToCheckoutDto(recent, user);
            }
        }

        // 3. Create internal pending PaymentOrder record before calling Razorpay
        String receipt = "rcpt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        PaymentOrder paymentOrder = new PaymentOrder(user, "RAZORPAY", tier, amount, currency, receipt, idempotencyKey);
        paymentOrder = paymentOrderRepository.save(paymentOrder);

        // 4. Call Razorpay API to generate the official Razorpay Order
        Map<String, String> notes = new LinkedHashMap<>();
        notes.put("userId", user.getId());
        notes.put("email", user.getEmail());
        notes.put("plan", tier.name());
        notes.put("internalOrderId", paymentOrder.getId());

        try {
            RazorpayOrderResponse rzpOrder = razorpayGateway.createOrder(amount, currency, receipt, notes);
            paymentOrder.setRazorpayOrderId(rzpOrder.id());
            paymentOrder.setUpdatedAt(Instant.now());
            paymentOrder = paymentOrderRepository.save(paymentOrder);
            logger.info("Successfully created Razorpay order {} for user {} and plan {}", rzpOrder.id(), user.getEmail(), tier);
        } catch (Exception e) {
            paymentOrder.setStatus("FAILED");
            paymentOrder.setUpdatedAt(Instant.now());
            paymentOrderRepository.save(paymentOrder);
            logger.error("Failed to create Razorpay order for internalOrderId {}: {}", paymentOrder.getId(), e.getMessage());
            throw e;
        }

        return mapToCheckoutDto(paymentOrder, user);
    }

    /**
     * Verifies the client-side checkout result against the Razorpay cryptographic signature and Razorpay API.
     */
    @Transactional
    public Map<String, Object> verifyPayment(String userId, String orderId, String paymentId, String signature) {
        if (orderId == null || orderId.isBlank()) {
            throw new InvalidPaymentRequestException("razorpayOrderId is required");
        }
        if (paymentId == null || paymentId.isBlank()) {
            throw new InvalidPaymentRequestException("razorpayPaymentId is required");
        }
        if (signature == null || signature.isBlank()) {
            throw new InvalidPaymentRequestException("razorpaySignature is required");
        }

        PaymentOrder order = paymentOrderRepository.findByRazorpayOrderId(orderId.trim())
                .orElseThrow(() -> new OrderNotFoundException("No order found with razorpayOrderId: " + orderId));

        // Security check: order must belong to authenticated requesting user
        if (!order.getUser().getId().equals(userId)) {
            logger.warn("Security violation: User {} attempted to verify order {} belonging to user {}",
                    userId, orderId, order.getUser().getId());
            throw new PaymentVerificationException("Payment order does not belong to the authenticated user");
        }

        // Signature check: HMAC-SHA256(order_id + "|" + payment_id, key_secret) in constant-time
        boolean signatureValid = razorpayGateway.verifyPaymentSignature(orderId, paymentId, signature);
        if (!signatureValid) {
            logger.warn("Invalid Razorpay payment signature for order {} and payment {}", orderId, paymentId);
            throw new PaymentVerificationException("Invalid payment signature verification failed");
        }

        // Idempotency: if order is already PAID, return active subscription status safely
        if (order.isPaid()) {
            logger.info("Payment order {} has already been paid and activated (idempotent verification request).", orderId);
            Subscription activeSub = subscriptionRepository.findTopByUserOrderByCreatedAtDesc(order.getUser()).orElse(null);
            EntitlementToken token = signingService.generateEntitlement(order.getUser(), activeSub);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("verified", true);
            result.put("status", "ALREADY_ACTIVE");
            result.put("message", "Payment has already been processed and subscription is active");
            result.put("orderId", orderId);
            result.put("paymentId", paymentId);
            result.put("subscription", activeSub);
            result.put("token", token);
            return result;
        }

        // Server-side check via trusted Razorpay API: confirm payment is captured and parameters match
        RazorpayPaymentResponse payment = razorpayGateway.fetchPayment(paymentId.trim());

        if (!order.getRazorpayOrderId().equals(payment.orderId())) {
            throw new PaymentVerificationException("Payment order mismatch: payment belongs to " + payment.orderId() + ", expected " + order.getRazorpayOrderId());
        }
        if (payment.amount() != order.getAmount()) {
            throw new PaymentVerificationException("Payment amount mismatch: received " + payment.amount() + ", expected " + order.getAmount());
        }
        if (!order.getCurrency().equalsIgnoreCase(payment.currency())) {
            throw new PaymentVerificationException("Payment currency mismatch: received " + payment.currency() + ", expected " + order.getCurrency());
        }
        if (!payment.captured() && !"captured".equalsIgnoreCase(payment.status())) {
            throw new PaymentVerificationException("Payment is not captured. Current provider status: " + payment.status());
        }

        // Update PaymentOrder record
        order.setRazorpayPaymentId(paymentId.trim());
        order.setRazorpaySignature(signature.trim());
        order.setStatus("PAID");
        order.setPaidAt(Instant.now());
        order.setUpdatedAt(Instant.now());
        paymentOrderRepository.save(order);

        // Activate Subscription
        Subscription subscription = activateSubscriptionForOrder(order, paymentId.trim(), "CLIENT_VERIFICATION");

        // Record Transaction for audit & deduplication
        String txEventId = "rzp_pay_" + paymentId.trim();
        if (!paymentTransactionRepository.existsByEventId(txEventId)) {
            PaymentTransaction tx = new PaymentTransaction(
                    order.getUser(),
                    subscription,
                    "RAZORPAY",
                    txEventId,
                    "payment.verified",
                    order.getAmount(),
                    order.getCurrency(),
                    "SUCCEEDED",
                    "Verified via server-side Razorpay API and signature verification"
            );
            tx.setOrderId(order.getRazorpayOrderId());
            tx.setPaymentId(paymentId.trim());
            paymentTransactionRepository.save(tx);
        }

        EntitlementToken entitlementToken = signingService.generateEntitlement(order.getUser(), subscription);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("verified", true);
        response.put("status", "ACTIVE");
        response.put("message", "Payment successfully verified and Pro subscription activated!");
        response.put("orderId", order.getRazorpayOrderId());
        response.put("paymentId", paymentId.trim());
        response.put("tier", subscription.getTier().name());
        response.put("tierDisplayName", subscription.getTier().getDisplayName());
        response.put("expiresAt", subscription.getCurrentPeriodEnd().toString());
        response.put("subscription", subscription);
        response.put("token", entitlementToken);

        return response;
    }

    /**
     * Activates or extends a subscription for a verified PaymentOrder.
     */
    @Transactional
    public Subscription activateSubscriptionForOrder(PaymentOrder order, String externalPaymentId, String source) {
        User user = order.getUser();
        SubscriptionTier tier = order.getPlan();

        Instant now = Instant.now();
        Instant periodEnd;
        long durationSeconds;

        String externalSubId = (order.getRazorpayOrderId() != null && !order.getRazorpayOrderId().isBlank())
                ? order.getRazorpayOrderId()
                : externalPaymentId;

        if (tier == SubscriptionTier.PRO_MONTHLY) {
            durationSeconds = 30L * 86400L;
        } else if (tier == SubscriptionTier.PRO_YEARLY) {
            durationSeconds = 365L * 86400L;
        } else {
            // LIFETIME
            durationSeconds = 100L * 365L * 86400L;
        }

        Optional<Subscription> currentSubOpt = subscriptionRepository.findTopByUserOrderByCreatedAtDesc(user);
        Subscription subscription;

        if (currentSubOpt.isPresent()) {
            Subscription existing = currentSubOpt.get();

            // If existing subscription was a complimentary grant, paid subscription takes authoritative precedence
            boolean isComplimentary = "COMPLIMENTARY".equalsIgnoreCase(existing.getPaymentProvider());

            if (existing.isActive() && !isComplimentary && existing.getTier() == tier && tier != SubscriptionTier.LIFETIME) {
                // Same tier renewal/extension
                Instant currentEnd = existing.getCurrentPeriodEnd() != null && existing.getCurrentPeriodEnd().isAfter(now)
                        ? existing.getCurrentPeriodEnd() : now;
                periodEnd = currentEnd.plusSeconds(durationSeconds);
                existing.setCurrentPeriodEnd(periodEnd);
                existing.setStatus("ACTIVE");
                existing.setExternalSubscriptionId(externalSubId);
                existing.setUpdatedAt(now);
                subscription = subscriptionRepository.save(existing);
                logger.info("Extended existing paid subscription for user {}: new expiry {}", user.getEmail(), periodEnd);
            } else {
                // Tier upgrade or replacement of expired / complimentary subscription
                periodEnd = now.plusSeconds(durationSeconds);
                existing.setTier(tier);
                existing.setStatus("ACTIVE");
                existing.setPaymentProvider("RAZORPAY");
                existing.setCurrentPeriodStart(now);
                existing.setCurrentPeriodEnd(periodEnd);
                existing.setExternalSubscriptionId(externalSubId);
                existing.setUpdatedAt(now);
                subscription = subscriptionRepository.save(existing);
                logger.info("Updated subscription for user {} to tier {}: expiry {}", user.getEmail(), tier, periodEnd);
            }
        } else {
            // Brand new subscription
            periodEnd = now.plusSeconds(durationSeconds);
            subscription = new Subscription(user, tier, "ACTIVE", "RAZORPAY", now, periodEnd);
            subscription.setExternalSubscriptionId(externalSubId);
            subscription = subscriptionRepository.save(subscription);
            logger.info("Created new subscription for user {} with tier {}: expiry {}", user.getEmail(), tier, periodEnd);
        }

        return subscription;
    }

    private SubscriptionTier validateAndParsePlan(String planStr) {
        return planCatalogService.resolvePlan(planStr).tier();
    }

    private String validateAndNormalizeCurrency(String currencyStr) {
        if (currencyStr == null || currencyStr.isBlank()) {
            return "INR";
        }
        String c = currencyStr.trim().toUpperCase();
        if (!"INR".equals(c) && !"USD".equals(c)) {
            throw new InvalidPaymentRequestException("Unsupported currency: '" + currencyStr + "'. Supported currencies: INR, USD");
        }
        return c;
    }

    private RazorpayCheckoutDto mapToCheckoutDto(PaymentOrder order, User user) {
        String checkoutUrl = "/api/checkout/razorpay/hosted?orderId=" + order.getRazorpayOrderId();
        return new RazorpayCheckoutDto(
                order.getRazorpayOrderId(),
                order.getId(),
                razorpayGateway.getKeyId(),
                order.getAmount(),
                order.getCurrency(),
                order.getPlan().name(),
                order.getPlan().getDisplayName(),
                user.getEmail(),
                user.getFullName() != null ? user.getFullName() : "Customer",
                order.getReceipt(),
                checkoutUrl,
                "RAZORPAY"
        );
    }
}
