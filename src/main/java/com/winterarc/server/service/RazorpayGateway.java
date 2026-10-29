package com.winterarc.server.service;

import com.winterarc.server.dto.RazorpayOrderResponse;
import com.winterarc.server.dto.RazorpayPaymentResponse;

import java.util.Map;

public interface RazorpayGateway {

    /**
     * Creates an order with Razorpay Orders API.
     */
    RazorpayOrderResponse createOrder(long amount, String currency, String receipt, Map<String, String> notes);

    /**
     * Fetches an order from Razorpay by order ID.
     */
    RazorpayOrderResponse fetchOrder(String orderId);

    /**
     * Fetches payment details from Razorpay by payment ID.
     */
    RazorpayPaymentResponse fetchPayment(String paymentId);

    /**
     * Verifies the client checkout signature: HMAC-SHA256(order_id + "|" + payment_id, key_secret).
     */
    boolean verifyPaymentSignature(String orderId, String paymentId, String signature);

    /**
     * Verifies the webhook signature: HMAC-SHA256(raw_body, webhook_secret).
     */
    boolean verifyWebhookSignature(String rawBody, String signature);

    /**
     * Returns the configured public Razorpay Key ID.
     */
    String getKeyId();
}
