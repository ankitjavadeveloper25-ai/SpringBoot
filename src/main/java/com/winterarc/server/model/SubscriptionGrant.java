package com.winterarc.server.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "subscription_grants")
public class SubscriptionGrant {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "user_email", nullable = false)
    private String userEmail;

    @Column(name = "subscription_id", length = 36)
    private String subscriptionId;

    @Column(name = "admin_id", nullable = false, length = 36)
    private String adminId;

    @Column(name = "admin_email", nullable = false)
    private String adminEmail;

    @Column(name = "grant_type", nullable = false, length = 50)
    private String grantType; // "COMPLIMENTARY", "PROMOTIONAL", "VIP_COMP"

    @Enumerated(EnumType.STRING)
    @Column(length = 50, nullable = false)
    private SubscriptionTier tier;

    private Integer durationDays; // null if permanent/lifetime

    @Column(nullable = false)
    private Instant startDate;

    private Instant expiryDate; // null if permanent

    @Column(length = 20, nullable = false)
    private String status; // "ACTIVE", "EXTENDED", "REVOKED", "EXPIRED"

    @Column(nullable = false, length = 1000)
    private String reason;

    private Instant revokedAt;
    private String revokedByAdminId;
    private String revocationReason;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;

    public SubscriptionGrant() {
        this.id = UUID.randomUUID().toString();
        this.grantType = "COMPLIMENTARY";
        this.status = "ACTIVE";
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public SubscriptionGrant(String userId, String userEmail, String subscriptionId,
                             String adminId, String adminEmail, String grantType,
                             SubscriptionTier tier, Integer durationDays,
                             Instant startDate, Instant expiryDate, String reason) {
        this();
        this.userId = userId;
        this.userEmail = userEmail;
        this.subscriptionId = subscriptionId;
        this.adminId = adminId;
        this.adminEmail = adminEmail;
        this.grantType = grantType != null ? grantType : "COMPLIMENTARY";
        this.tier = tier;
        this.durationDays = durationDays;
        this.startDate = startDate;
        this.expiryDate = expiryDate;
        this.reason = reason;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUserEmail() { return userEmail; }
    public void setUserEmail(String userEmail) { this.userEmail = userEmail; }

    public String getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }

    public String getAdminId() { return adminId; }
    public void setAdminId(String adminId) { this.adminId = adminId; }

    public String getAdminEmail() { return adminEmail; }
    public void setAdminEmail(String adminEmail) { this.adminEmail = adminEmail; }

    public String getGrantType() { return grantType; }
    public void setGrantType(String grantType) { this.grantType = grantType; }

    public SubscriptionTier getTier() { return tier; }
    public void setTier(SubscriptionTier tier) { this.tier = tier; }

    public Integer getDurationDays() { return durationDays; }
    public void setDurationDays(Integer durationDays) { this.durationDays = durationDays; }

    public Instant getStartDate() { return startDate; }
    public void setStartDate(Instant startDate) { this.startDate = startDate; }

    public Instant getExpiryDate() { return expiryDate; }
    public void setExpiryDate(Instant expiryDate) { this.expiryDate = expiryDate; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }

    public String getRevokedByAdminId() { return revokedByAdminId; }
    public void setRevokedByAdminId(String revokedByAdminId) { this.revokedByAdminId = revokedByAdminId; }

    public String getRevocationReason() { return revocationReason; }
    public void setRevocationReason(String revocationReason) { this.revocationReason = revocationReason; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
