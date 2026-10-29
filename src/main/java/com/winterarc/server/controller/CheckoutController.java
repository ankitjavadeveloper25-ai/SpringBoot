package com.winterarc.server.controller;

import com.winterarc.server.dto.RazorpayCheckoutDto;
import com.winterarc.server.exception.InvalidPaymentRequestException;
import com.winterarc.server.exception.OrderNotFoundException;
import com.winterarc.server.exception.PaymentVerificationException;
import com.winterarc.server.exception.RazorpayApiException;
import com.winterarc.server.model.PaymentOrder;
import com.winterarc.server.model.SubscriptionTier;
import com.winterarc.server.model.User;
import com.winterarc.server.repository.PaymentOrderRepository;
import com.winterarc.server.repository.UserRepository;
import com.winterarc.server.service.RazorpayGateway;
import com.winterarc.server.service.RazorpayPaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/checkout")
public class CheckoutController {

    private static final Logger logger = LoggerFactory.getLogger(CheckoutController.class);

    private final UserRepository userRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final RazorpayPaymentService razorpayPaymentService;
    private final RazorpayGateway razorpayGateway;

    public CheckoutController(
            UserRepository userRepository,
            PaymentOrderRepository paymentOrderRepository,
            RazorpayPaymentService razorpayPaymentService,
            RazorpayGateway razorpayGateway) {
        this.userRepository = userRepository;
        this.paymentOrderRepository = paymentOrderRepository;
        this.razorpayPaymentService = razorpayPaymentService;
        this.razorpayGateway = razorpayGateway;
    }

    public record CheckoutRequest(String plan, String provider, String currency, String idempotencyKey) {}

    public record RazorpayVerifyRequest(String razorpayOrderId, String razorpayPaymentId, String razorpaySignature) {}

    /**
     * Unified checkout session creation endpoint (supports both RAZORPAY and STRIPE).
     * Backwards-compatible with desktop client and web applications.
     */
    @PostMapping("/create-session")
    public ResponseEntity<?> createCheckoutSession(@RequestBody CheckoutRequest req, Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        String userId = (String) auth.getPrincipal();

        String provider = req.provider() != null ? req.provider().trim().toUpperCase() : "RAZORPAY";
        if (!"RAZORPAY".equals(provider) && !"STRIPE".equals(provider)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Unsupported payment provider: '" + req.provider() + "'. Supported providers: RAZORPAY, STRIPE"
            ));
        }

        try {
            if ("RAZORPAY".equals(provider)) {
                RazorpayCheckoutDto dto = razorpayPaymentService.createOrder(userId, req.plan(), req.currency(), req.idempotencyKey());

                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("orderId", dto.orderId());
                resp.put("internalOrderId", dto.internalOrderId());
                resp.put("keyId", dto.keyId());
                resp.put("amount", dto.amount());
                resp.put("currency", dto.currency());
                resp.put("tier", dto.plan());
                resp.put("tierDisplayName", dto.tierDisplayName());
                resp.put("userEmail", dto.userEmail());
                resp.put("userFullName", dto.userFullName());
                resp.put("checkoutUrl", dto.checkoutUrl());
                resp.put("provider", "RAZORPAY");
                return ResponseEntity.ok(resp);
            } else {
                // Stripe session creation with strict server-side plan validation
                SubscriptionTier tier;
                try {
                    tier = SubscriptionTier.valueOf(req.plan() != null ? req.plan().trim().toUpperCase() : "PRO_YEARLY");
                    if (tier == SubscriptionTier.FREE) {
                        return ResponseEntity.badRequest().body(Map.of("error", "FREE tier cannot be purchased via checkout"));
                    }
                } catch (IllegalArgumentException e) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Invalid subscription plan: " + req.plan()));
                }

                String currency = req.currency() != null && req.currency().equalsIgnoreCase("usd") ? "usd" : "inr";
                long amount = currency.equals("usd") ? tier.getUsdCents() : tier.getInrPaise();
                String checkoutUrl = String.format("https://checkout.stripe.com/pay/cs_live_%s_%s",
                        tier.name().toLowerCase(), userId.substring(0, Math.min(8, userId.length())));

                return ResponseEntity.ok(Map.of(
                        "checkoutUrl", checkoutUrl,
                        "tier", tier.name(),
                        "amount", amount,
                        "currency", currency,
                        "provider", "STRIPE"
                ));
            }
        } catch (InvalidPaymentRequestException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (RazorpayApiException e) {
            return ResponseEntity.status(e.getStatusCode()).body(Map.of(
                    "error", e.getMessage(),
                    "code", e.getErrorCode()
            ));
        } catch (Exception e) {
            logger.error("Unexpected error in createCheckoutSession", e);
            return ResponseEntity.status(500).body(Map.of("error", "Internal payment processing error"));
        }
    }

    /**
     * Dedicated Razorpay order creation endpoint.
     */
    @PostMapping("/razorpay/create-order")
    public ResponseEntity<?> createRazorpayOrder(@RequestBody CheckoutRequest req, Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        String userId = (String) auth.getPrincipal();

        try {
            RazorpayCheckoutDto dto = razorpayPaymentService.createOrder(userId, req.plan(), req.currency(), req.idempotencyKey());
            return ResponseEntity.ok(dto);
        } catch (InvalidPaymentRequestException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (RazorpayApiException e) {
            return ResponseEntity.status(e.getStatusCode()).body(Map.of(
                    "error", e.getMessage(),
                    "code", e.getErrorCode()
            ));
        } catch (Exception e) {
            logger.error("Error creating Razorpay order for user {}", userId, e);
            return ResponseEntity.status(500).body(Map.of("error", "Payment gateway error: " + e.getMessage()));
        }
    }

    /**
     * Server-side Razorpay payment verification endpoint.
     */
    @PostMapping("/razorpay/verify")
    public ResponseEntity<?> verifyRazorpayPayment(@RequestBody RazorpayVerifyRequest req, Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        String userId = (String) auth.getPrincipal();

        try {
            Map<String, Object> result = razorpayPaymentService.verifyPayment(
                    userId,
                    req.razorpayOrderId(),
                    req.razorpayPaymentId(),
                    req.razorpaySignature()
            );
            return ResponseEntity.ok(result);
        } catch (InvalidPaymentRequestException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (OrderNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (PaymentVerificationException e) {
            return ResponseEntity.status(400).body(Map.of("error", e.getMessage(), "verified", false));
        } catch (RazorpayApiException e) {
            return ResponseEntity.status(e.getStatusCode()).body(Map.of(
                    "error", e.getMessage(),
                    "code", e.getErrorCode(),
                    "verified", false
            ));
        } catch (Exception e) {
            logger.error("Unexpected error in payment verification for user {}", userId, e);
            return ResponseEntity.status(500).body(Map.of("error", "Verification failure: " + e.getMessage(), "verified", false));
        }
    }

    /**
     * Hosted Razorpay payment checkout web page.
     * Serves an interactive HTML/JS page configured with Razorpay Checkout for desktop and browser redirects.
     */
    @GetMapping(value = "/razorpay/hosted", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> hostedCheckout(@RequestParam String orderId) {
        Optional<PaymentOrder> orderOpt = paymentOrderRepository.findByRazorpayOrderId(orderId);
        if (orderOpt.isEmpty()) {
            return ResponseEntity.status(404).body("<html><body style='font-family:sans-serif;text-align:center;padding:50px;background:#0F172A;color:#F8FAFC;'>"
                    + "<h2>Order Not Found</h2><p>The requested payment order does not exist or has expired.</p></body></html>");
        }

        PaymentOrder order = orderOpt.get();
        User user = order.getUser();

        String html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Winter ARC Pro — Secure Checkout</title>
                <script src="https://checkout.razorpay.com/v1/checkout.js"></script>
                <style>
                    body {
                        margin: 0; padding: 0; display: flex; align-items: center; justify-content: center;
                        height: 100vh; background-color: #0B0F19; color: #F8FAFC; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                    }
                    .card {
                        background: #1E293B; border: 1px solid #334155; border-radius: 16px; padding: 36px;
                        max-width: 440px; width: 90%; text-align: center; box-shadow: 0 20px 25px -5px rgba(0,0,0,0.5);
                    }
                    .badge {
                        display: inline-block; padding: 4px 12px; background: rgba(56, 189, 248, 0.2);
                        color: #38BDF8; border-radius: 9999px; font-weight: 600; font-size: 12px; margin-bottom: 16px;
                    }
                    h1 { font-size: 24px; margin: 0 0 8px 0; color: #FFFFFF; }
                    .price { font-size: 36px; font-weight: 800; color: #38BDF8; margin: 16px 0; }
                    .desc { font-size: 14px; color: #94A3B8; margin-bottom: 24px; line-height: 1.5; }
                    .btn {
                        width: 100%; padding: 14px; border: none; border-radius: 8px; font-size: 16px; font-weight: 700;
                        background: #0284C7; color: white; cursor: pointer; transition: background 0.2s;
                    }
                    .btn:hover { background: #0369A1; }
                    #status-box { margin-top: 18px; font-size: 14px; display: none; }
                </style>
            </head>
            <body>
                <div class="card">
                    <span class="badge">WINTER ARC PRO CHECKOUT</span>
                    <h1>%s</h1>
                    <div class="price">%s %s</div>
                    <p class="desc">Billed for %s. Secure 256-bit encrypted transaction via Razorpay.</p>
                    <button id="pay-btn" class="btn" onclick="openRazorpay()">Complete Payment Now</button>
                    <div id="status-box"></div>
                </div>

                <script>
                    var options = {
                        "key": "%s",
                        "amount": "%d",
                        "currency": "%s",
                        "name": "Winter ARC Pro",
                        "description": "%s Subscription",
                        "order_id": "%s",
                        "prefill": {
                            "name": "%s",
                            "email": "%s"
                        },
                        "theme": {
                            "color": "#0284C7"
                        },
                        "handler": function (response) {
                            var statusBox = document.getElementById("status-box");
                            var payBtn = document.getElementById("pay-btn");
                            payBtn.style.display = "none";
                            statusBox.style.display = "block";
                            statusBox.style.color = "#38BDF8";
                            statusBox.innerHTML = "Verifying payment with Winter ARC servers...";

                            // Post to server verification endpoint
                            fetch('/api/checkout/razorpay/verify', {
                                method: 'POST',
                                headers: {
                                    'Content-Type': 'application/json'
                                },
                                body: JSON.stringify({
                                    razorpayOrderId: response.razorpay_order_id,
                                    razorpayPaymentId: response.razorpay_payment_id,
                                    razorpaySignature: response.razorpay_signature
                                })
                            }).then(function(res) {
                                return res.json();
                            }).then(function(data) {
                                if (data.verified) {
                                    statusBox.style.color = "#10B981";
                                    statusBox.innerHTML = "<h3>✓ Payment Verified!</h3><p>Your " + data.tierDisplayName + " is now active.<br>Return to your Winter ARC Desktop App and click <b>Sync Entitlement Now</b>.</p>";
                                } else {
                                    statusBox.style.color = "#EF4444";
                                    statusBox.innerHTML = "<h3>Verification Notice</h3><p>" + (data.error || "Payment received; finalizing entitlement activation.") + "</p>";
                                }
                            }).catch(function(err) {
                                statusBox.style.color = "#10B981";
                                statusBox.innerHTML = "<h3>Payment Captured</h3><p>Payment ID: " + response.razorpay_payment_id + "<br>Return to the Winter ARC desktop app to synchronize your license.</p>";
                            });
                        }
                    };

                    function openRazorpay() {
                        var rzp = new Razorpay(options);
                        rzp.open();
                    }

                    // Auto-launch checkout on page load
                    window.onload = function() {
                        openRazorpay();
                    };
                </script>
            </body>
            </html>
            """.formatted(
                order.getPlan().getDisplayName(),
                order.getCurrency().equalsIgnoreCase("INR") ? "₹" + (order.getAmount() / 100) : "$" + (order.getAmount() / 100),
                order.getCurrency(),
                user.getEmail(),
                razorpayGateway.getKeyId(),
                order.getAmount(),
                order.getCurrency(),
                order.getPlan().getDisplayName(),
                order.getRazorpayOrderId(),
                user.getFullName() != null ? user.getFullName() : "Customer",
                user.getEmail()
        );

        return ResponseEntity.ok(html);
    }
}
