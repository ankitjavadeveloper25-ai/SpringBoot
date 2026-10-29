package com.winterarc.server.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RazorpayOrderResponse(
    String id,
    String entity,
    long amount,
    @JsonProperty("amount_paid") long amountPaid,
    @JsonProperty("amount_due") long amountDue,
    String currency,
    String receipt,
    String status,
    int attempts,
    Map<String, String> notes,
    @JsonProperty("created_at") long createdAt
) {
    public RazorpayOrderResponse(String id, String currency, long amount, String status, String receipt) {
        this(id, "order", amount, 0L, amount, currency, receipt, status, 0, Map.of(), System.currentTimeMillis() / 1000L);
    }
}
