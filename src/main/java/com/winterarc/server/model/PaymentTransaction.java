package com.winterarc.server.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_transactions")
public class PaymentTransaction {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id")
    private Subscription subscription;

    @Column(length = 50, nullable = false)
    private String provider; // "STRIPE", "RAZORPAY"

    @Column(unique = true, nullable = false)
    private String eventId; // Idempotency key from webhook payload

    @Column(length = 100, nullable = false)
    private String eventType;

    private long amountCents;

    @Column(length = 10, nullable = false)
    private String currency;

    @Column(length = 50, nullable = false)
    private String status; // "SUCCEEDED", "FAILED", "REFUNDED"

    @Column(name = "order_id", length = 100)
    private String orderId;

    @Column(name = "payment_id", length = 100)
    private String paymentId;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String rawPayload;

    private Instant createdAt;

    public PaymentTransaction() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
    }

    public PaymentTransaction(User user, Subscription subscription, String provider, String eventId,
                              String eventType, long amountCents, String currency, String status, String rawPayload) {
        this();
        this.user = user;
        this.subscription = subscription;
        this.provider = provider;
        this.eventId = eventId;
        this.eventType = eventType;
        this.amountCents = amountCents;
        this.currency = currency;
        this.status = status;
        this.rawPayload = rawPayload;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public Subscription getSubscription() { return subscription; }
    public void setSubscription(Subscription subscription) { this.subscription = subscription; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public long getAmountCents() { return amountCents; }
    public void setAmountCents(long amountCents) { this.amountCents = amountCents; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }

    public String getRawPayload() { return rawPayload; }
    public void setRawPayload(String rawPayload) { this.rawPayload = rawPayload; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
