package com.winterarc.server.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RazorpayPaymentResponse(
    String id,
    String entity,
    long amount,
    String currency,
    String status,
    @JsonProperty("order_id") String orderId,
    @JsonProperty("invoice_id") String invoiceId,
    boolean international,
    String method,
    @JsonProperty("amount_refunded") long amountRefunded,
    @JsonProperty("refund_status") String refundStatus,
    boolean captured,
    String description,
    String email,
    String contact,
    Map<String, String> notes,
    long fee,
    long tax,
    @JsonProperty("error_code") String errorCode,
    @JsonProperty("error_description") String errorDescription,
    @JsonProperty("created_at") long createdAt
) {
    public RazorpayPaymentResponse(String id, String orderId, long amount, String currency, String status, boolean captured) {
        this(id, "payment", amount, currency, status, orderId, null, false, "card", 0L, null, captured,
             "Winter ARC Pro Subscription", null, null, Map.of(), 0L, 0L, null, null, System.currentTimeMillis() / 1000L);
    }
}
