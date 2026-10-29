package com.winterarc.server.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_orders", indexes = {
    @Index(name = "idx_orders_rzp_id", columnList = "razorpayOrderId"),
    @Index(name = "idx_orders_user_id", columnList = "user_id"),
    @Index(name = "idx_orders_status", columnList = "status")
})
public class PaymentOrder {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(length = 50, nullable = false)
    private String provider; // "RAZORPAY", "STRIPE"

    @Enumerated(EnumType.STRING)
    @Column(length = 50, nullable = false)
    private SubscriptionTier plan;

    @Column(nullable = false)
    private long amount; // in smallest currency unit (paise/cents)

    @Column(length = 10, nullable = false)
    private String currency; // "INR", "USD"

    @Column(name = "razorpay_order_id", length = 100, unique = true)
    private String razorpayOrderId;

    @Column(name = "razorpay_payment_id", length = 100)
    private String razorpayPaymentId;

    @Column(name = "razorpay_signature", length = 255)
    private String razorpaySignature;

    @Column(length = 50, nullable = false)
    private String status; // "PENDING", "PAID", "FAILED", "CANCELLED"

    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    @Column(length = 100, nullable = false)
    private String receipt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant paidAt;

    public PaymentOrder() {
        this.id = UUID.randomUUID().toString();
        this.status = "PENDING";
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public PaymentOrder(User user, String provider, SubscriptionTier plan, long amount,
                        String currency, String receipt, String idempotencyKey) {
        this();
        this.user = user;
        this.provider = provider;
        this.plan = plan;
        this.amount = amount;
        this.currency = currency;
        this.receipt = receipt;
        this.idempotencyKey = idempotencyKey;
    }

    public PaymentOrder(User user, String razorpayOrderId, long amount, String currency,
                        SubscriptionTier plan, String receipt) {
        this();
        this.user = user;
        this.provider = "RAZORPAY";
        this.razorpayOrderId = razorpayOrderId;
        this.amount = amount;
        this.currency = currency;
        this.plan = plan;
        this.receipt = receipt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public SubscriptionTier getPlan() { return plan; }
    public void setPlan(SubscriptionTier plan) { this.plan = plan; }

    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getRazorpayOrderId() { return razorpayOrderId; }
    public void setRazorpayOrderId(String razorpayOrderId) { this.razorpayOrderId = razorpayOrderId; }

    public String getRazorpayPaymentId() { return razorpayPaymentId; }
    public void setRazorpayPaymentId(String razorpayPaymentId) { this.razorpayPaymentId = razorpayPaymentId; }

    public String getRazorpaySignature() { return razorpaySignature; }
    public void setRazorpaySignature(String razorpaySignature) { this.razorpaySignature = razorpaySignature; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getReceipt() { return receipt; }
    public void setReceipt(String receipt) { this.receipt = receipt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }

    public boolean isPaid() {
        return "PAID".equalsIgnoreCase(this.status);
    }
}
