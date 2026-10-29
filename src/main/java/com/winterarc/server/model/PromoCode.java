package com.winterarc.server.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "promo_codes")
public class PromoCode {

    @Id
    @Column(length = 36)
    private String id;

    @Column(unique = true, nullable = false, length = 32)
    private String code;

    @Column(name = "discount_percent", nullable = false)
    private int discountPercent;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier_grant", length = 32)
    private SubscriptionTier tierGrant;

    @Column(name = "duration_days")
    private Integer durationDays;

    @Column(name = "max_redemptions", nullable = false)
    private int maxRedemptions;

    @Column(name = "times_redeemed", nullable = false)
    private int timesRedeemed;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    public PromoCode() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
        this.active = true;
        this.timesRedeemed = 0;
    }

    public PromoCode(String code, int discountPercent, SubscriptionTier tierGrant,
                     Integer durationDays, int maxRedemptions, Instant expiresAt) {
        this();
        this.code = code != null ? code.trim().toUpperCase() : UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        this.discountPercent = discountPercent;
        this.tierGrant = tierGrant;
        this.durationDays = durationDays;
        this.maxRedemptions = maxRedemptions > 0 ? maxRedemptions : 100;
        this.expiresAt = expiresAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public int getDiscountPercent() { return discountPercent; }
    public void setDiscountPercent(int discountPercent) { this.discountPercent = discountPercent; }

    public SubscriptionTier getTierGrant() { return tierGrant; }
    public void setTierGrant(SubscriptionTier tierGrant) { this.tierGrant = tierGrant; }

    public Integer getDurationDays() { return durationDays; }
    public void setDurationDays(Integer durationDays) { this.durationDays = durationDays; }

    public int getMaxRedemptions() { return maxRedemptions; }
    public void setMaxRedemptions(int maxRedemptions) { this.maxRedemptions = maxRedemptions; }

    public int getTimesRedeemed() { return timesRedeemed; }
    public void setTimesRedeemed(int timesRedeemed) { this.timesRedeemed = timesRedeemed; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
