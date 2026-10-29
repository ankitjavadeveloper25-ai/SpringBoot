package com.winterarc.server.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.nio.charset.StandardCharsets;

/**
 * Canonical Entitlement Token representation for Winter ARC Server.
 * Cryptographically signed using Ed25519 digital signatures.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class EntitlementToken {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String EXPECTED_PRODUCT_ID = "winter-arc-desktop";
    public static final long DEFAULT_GRACE_PERIOD_SECONDS = 14L * 86400L; // 14 days

    private int schemaVersion;
    private String productId;
    private String tokenId;
    private String keyId;
    private String userId;
    private String email;
    private SubscriptionTier tier;
    private long issuedAt;
    private long expiresAt;
    private long offlineGracePeriodSeconds;
    private String signature;

    public EntitlementToken() {
        this.schemaVersion = CURRENT_SCHEMA_VERSION;
        this.productId = EXPECTED_PRODUCT_ID;
        this.tier = SubscriptionTier.FREE;
        this.issuedAt = System.currentTimeMillis() / 1000L;
        this.expiresAt = this.issuedAt + (365L * 86400L);
        this.offlineGracePeriodSeconds = DEFAULT_GRACE_PERIOD_SECONDS;
        this.signature = "";
    }

    public EntitlementToken(int schemaVersion, String productId, String tokenId, String keyId,
                            String userId, String email, SubscriptionTier tier,
                            long issuedAt, long expiresAt, long offlineGracePeriodSeconds,
                            String signature) {
        this.schemaVersion = schemaVersion;
        this.productId = productId;
        this.tokenId = tokenId;
        this.keyId = keyId;
        this.userId = userId;
        this.email = email;
        this.tier = tier != null ? tier : SubscriptionTier.FREE;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.offlineGracePeriodSeconds = offlineGracePeriodSeconds;
        this.signature = signature;
    }

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }

    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }

    public String getTokenId() { return tokenId; }
    public void setTokenId(String tokenId) { this.tokenId = tokenId; }

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public SubscriptionTier getTier() { return tier; }
    public void setTier(SubscriptionTier tier) { this.tier = tier != null ? tier : SubscriptionTier.FREE; }

    public long getIssuedAt() { return issuedAt; }
    public void setIssuedAt(long issuedAt) { this.issuedAt = issuedAt; }

    public long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }

    public long getOfflineGracePeriodSeconds() { return offlineGracePeriodSeconds; }
    public void setOfflineGracePeriodSeconds(long offlineGracePeriodSeconds) {
        this.offlineGracePeriodSeconds = offlineGracePeriodSeconds;
    }

    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }

    /**
     * Deterministic canonical UTF-8 bytes signed by the Ed25519 private key.
     * Both desktop client and backend server match this exact sequence.
     */
    public byte[] toCanonicalBytes() {
        String canonical = "schemaVersion=" + schemaVersion + "\n"
            + "productId=" + (productId != null ? productId : "") + "\n"
            + "tokenId=" + (tokenId != null ? tokenId : "") + "\n"
            + "keyId=" + (keyId != null ? keyId : "") + "\n"
            + "userId=" + (userId != null ? userId : "") + "\n"
            + "email=" + (email != null ? email : "") + "\n"
            + "tier=" + (tier != null ? tier.name() : "FREE") + "\n"
            + "issuedAt=" + issuedAt + "\n"
            + "expiresAt=" + expiresAt + "\n"
            + "offlineGracePeriodSeconds=" + offlineGracePeriodSeconds;
        return canonical.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isExpired(long currentEpochSecond) {
        return currentEpochSecond > expiresAt;
    }

    public boolean isWithinOfflineGrace(long currentEpochSecond) {
        return currentEpochSecond > expiresAt && currentEpochSecond <= (expiresAt + offlineGracePeriodSeconds);
    }

    public boolean isClockRollback(long currentEpochSecond) {
        return currentEpochSecond < (issuedAt - 300L);
    }
}
