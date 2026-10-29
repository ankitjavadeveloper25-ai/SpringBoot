package com.winterarc.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.winterarc.server.config.RazorpayConfig;
import com.winterarc.server.dto.RazorpayOrderResponse;
import com.winterarc.server.dto.RazorpayPaymentResponse;
import com.winterarc.server.exception.RazorpayApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class RazorpayGatewayImpl implements RazorpayGateway {

    private static final Logger logger = LoggerFactory.getLogger(RazorpayGatewayImpl.class);

    private final RazorpayConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @org.springframework.beans.factory.annotation.Autowired
    public RazorpayGatewayImpl(RazorpayConfig config) {
        this(config, new ObjectMapper(), HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofMillis(config.getConnectTimeoutMs()))
            .build());
    }

    public RazorpayGatewayImpl(RazorpayConfig config, ObjectMapper objectMapper, HttpClient httpClient) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public String getKeyId() {
        return config.getKeyId();
    }

    @Override
    public RazorpayOrderResponse createOrder(long amount, String currency, String receipt, Map<String, String> notes) {
        String url = config.getApiBaseUrl() + "/orders";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amount);
        body.put("currency", currency != null ? currency.toUpperCase() : "INR");
        body.put("receipt", receipt);
        if (notes != null && !notes.isEmpty()) {
            body.put("notes", notes);
        }

        try {
            String jsonPayload = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", getBasicAuthHeader())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofMillis(config.getReadTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return objectMapper.readValue(response.body(), RazorpayOrderResponse.class);
            } else {
                throw parseRazorpayError(response.statusCode(), response.body());
            }
        } catch (HttpTimeoutException e) {
            logger.error("Razorpay createOrder request timed out after {}ms", config.getReadTimeoutMs());
            throw new RazorpayApiException(504, "GATEWAY_TIMEOUT", "Razorpay order creation timed out. Please try again.");
        } catch (IOException | InterruptedException e) {
            logger.error("Network failure communicating with Razorpay Orders API: {}", e.getMessage());
            throw new RazorpayApiException(502, "BAD_GATEWAY", "Failed to communicate with Razorpay payment gateway: " + e.getMessage());
        }
    }

    @Override
    public RazorpayOrderResponse fetchOrder(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("Razorpay order ID cannot be null or blank");
        }
        String url = config.getApiBaseUrl() + "/orders/" + orderId.trim();

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", getBasicAuthHeader())
                .header("Accept", "application/json")
                .timeout(Duration.ofMillis(config.getReadTimeoutMs()))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return objectMapper.readValue(response.body(), RazorpayOrderResponse.class);
            } else {
                throw parseRazorpayError(response.statusCode(), response.body());
            }
        } catch (HttpTimeoutException e) {
            logger.error("Razorpay fetchOrder request timed out for orderId={}", orderId);
            throw new RazorpayApiException(504, "GATEWAY_TIMEOUT", "Razorpay fetch order timed out");
        } catch (IOException | InterruptedException e) {
            logger.error("Network failure fetching Razorpay orderId={}: {}", orderId, e.getMessage());
            throw new RazorpayApiException(502, "BAD_GATEWAY", "Failed to fetch Razorpay order: " + e.getMessage());
        }
    }

    @Override
    public RazorpayPaymentResponse fetchPayment(String paymentId) {
        if (paymentId == null || paymentId.isBlank()) {
            throw new IllegalArgumentException("Razorpay payment ID cannot be null or blank");
        }
        String url = config.getApiBaseUrl() + "/payments/" + paymentId.trim();

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", getBasicAuthHeader())
                .header("Accept", "application/json")
                .timeout(Duration.ofMillis(config.getReadTimeoutMs()))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return objectMapper.readValue(response.body(), RazorpayPaymentResponse.class);
            } else {
                throw parseRazorpayError(response.statusCode(), response.body());
            }
        } catch (HttpTimeoutException e) {
            logger.error("Razorpay fetchPayment request timed out for paymentId={}", paymentId);
            throw new RazorpayApiException(504, "GATEWAY_TIMEOUT", "Razorpay fetch payment timed out");
        } catch (IOException | InterruptedException e) {
            logger.error("Network failure fetching Razorpay paymentId={}: {}", paymentId, e.getMessage());
            throw new RazorpayApiException(502, "BAD_GATEWAY", "Failed to fetch Razorpay payment: " + e.getMessage());
        }
    }

    @Override
    public boolean verifyPaymentSignature(String orderId, String paymentId, String signature) {
        if (orderId == null || orderId.isBlank()
            || paymentId == null || paymentId.isBlank()
            || signature == null || signature.isBlank()) {
            return false;
        }

        String keySecret = config.getKeySecret();
        if (keySecret == null || keySecret.isBlank()) {
            logger.error("Cannot verify payment signature: Razorpay key secret is not configured");
            return false;
        }

        try {
            String payload = orderId.trim() + "|" + paymentId.trim();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] computed = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String computedHex = HexFormat.of().formatHex(computed);

            return MessageDigest.isEqual(
                computedHex.getBytes(StandardCharsets.UTF_8),
                signature.trim().getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            logger.error("Error computing payment signature HMAC", e);
            return false;
        }
    }

    @Override
    public boolean verifyWebhookSignature(String rawBody, String signature) {
        if (rawBody == null || signature == null || signature.isBlank()) {
            return false;
        }

        String webhookSecret = config.getWebhookSecret();
        if (webhookSecret == null || webhookSecret.isBlank()) {
            logger.error("Cannot verify webhook signature: Razorpay webhook secret is not configured");
            return false;
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] computed = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            String computedHex = HexFormat.of().formatHex(computed);

            return MessageDigest.isEqual(
                computedHex.getBytes(StandardCharsets.UTF_8),
                signature.trim().getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            logger.error("Error computing webhook signature HMAC", e);
            return false;
        }
    }

    private String getBasicAuthHeader() {
        String credentials = config.getKeyId() + ":" + config.getKeySecret();
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private RazorpayApiException parseRazorpayError(int statusCode, String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode errorNode = root.path("error");
            String code = errorNode.path("code").asText("RAZORPAY_ERROR");
            String desc = errorNode.path("description").asText("Razorpay error: " + responseBody);
            return new RazorpayApiException(statusCode, code, desc);
        } catch (Exception ignored) {
            return new RazorpayApiException(statusCode, "RAZORPAY_ERROR", "Razorpay error: HTTP " + statusCode);
        }
    }
}
