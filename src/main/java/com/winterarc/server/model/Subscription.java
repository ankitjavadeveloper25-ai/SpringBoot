package com.winterarc.server.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "subscriptions")
public class Subscription {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(length = 50, nullable = false)
    private SubscriptionTier tier;

    @Column(length = 50, nullable = false)
    private String status; // "ACTIVE", "PAST_DUE", "CANCELLED", "EXPIRED"

    @Column(length = 50, nullable = false)
    private String paymentProvider; // "STRIPE", "RAZORPAY", "MANUAL"

    private String externalSubscriptionId;
    private String externalCustomerId;

    private Instant currentPeriodStart;
    private Instant currentPeriodEnd;
    private Instant cancelledAt;

    private Instant createdAt;
    private Instant updatedAt;

    public Subscription() {
        this.id = UUID.randomUUID().toString();
        this.tier = SubscriptionTier.FREE;
        this.status = "ACTIVE";
        this.paymentProvider = "MANUAL";
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Subscription(User user, SubscriptionTier tier, String status, String paymentProvider,
                        Instant currentPeriodStart, Instant currentPeriodEnd) {
        this();
        this.user = user;
        this.tier = tier;
        this.status = status;
        this.paymentProvider = paymentProvider;
        this.currentPeriodStart = currentPeriodStart;
        this.currentPeriodEnd = currentPeriodEnd;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public SubscriptionTier getTier() { return tier; }
    public void setTier(SubscriptionTier tier) { this.tier = tier; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getPaymentProvider() { return paymentProvider; }
    public void setPaymentProvider(String paymentProvider) { this.paymentProvider = paymentProvider; }

    public String getExternalSubscriptionId() { return externalSubscriptionId; }
    public void setExternalSubscriptionId(String externalSubscriptionId) { this.externalSubscriptionId = externalSubscriptionId; }

    public String getExternalCustomerId() { return externalCustomerId; }
    public void setExternalCustomerId(String externalCustomerId) { this.externalCustomerId = externalCustomerId; }

    public Instant getCurrentPeriodStart() { return currentPeriodStart; }
    public void setCurrentPeriodStart(Instant currentPeriodStart) { this.currentPeriodStart = currentPeriodStart; }

    public Instant getCurrentPeriodEnd() { return currentPeriodEnd; }
    public void setCurrentPeriodEnd(Instant currentPeriodEnd) { this.currentPeriodEnd = currentPeriodEnd; }

    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public boolean isActive() {
        return "ACTIVE".equalsIgnoreCase(status) && (currentPeriodEnd == null || currentPeriodEnd.isAfter(Instant.now()));
    }
}
