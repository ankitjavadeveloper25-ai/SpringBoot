package com.winterarc.server.dto;

public record RazorpayCheckoutDto(
    String orderId,
    String internalOrderId,
    String keyId,
    long amount,
    String currency,
    String plan,
    String tierDisplayName,
    String userEmail,
    String userFullName,
    String receipt,
    String checkoutUrl,
    String provider
) {}
