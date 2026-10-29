package com.winterarc.server.service;

import com.winterarc.server.model.EntitlementToken;
import com.winterarc.server.model.Subscription;
import com.winterarc.server.model.SubscriptionTier;
import com.winterarc.server.model.User;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enterprise Ed25519 Entitlement Signing Service.
 * Implements server-side private key signing and verification with key rotation support.
 */
@Service
public class EntitlementSigningService {
    private static final Logger logger = LoggerFactory.getLogger(EntitlementSigningService.class);

    public static final String DEFAULT_DEV_KEY_ID = "ed25519-2026-v1";
    public static final String DEV_PRIVATE_KEY_B64 = "MC4CAQAwBQYDK2VwBCIEIIFVzMh7VkMNYgPxVtTe2NY/8dbuitHEwKYw9ojUwfWM";
    public static final String DEV_PUBLIC_KEY_B64 = "MCowBQYDK2VwAyEAymrcZQtlmSRFGVI/sW+IGNUHFN4ksBAq8iVjfjuDohg=";

    public static final String ROTATION_V2_KEY_ID = "ed25519-2026-v2";
    public static final String ROTATION_V2_PRIVATE_KEY_B64 = "MC4CAQAwBQYDK2VwBCIEIIZBTMPTIb3IoNJsDJHHgzU3oev+5YZIaNfAyKYDiUkj";
    public static final String ROTATION_V2_PUBLIC_KEY_B64 = "MCowBQYDK2VwAyEAKk4xvrcpWvJCUqQXZW4yQ9LGZrjez6yPBuOK4VMeePg=";

    private final String activeKeyId;
    private final String configuredPrivateKey;
    private final String configuredPublicKey;
    private final String productId;
    private final int schemaVersion;
    private final long offlineGraceSeconds;
    private final boolean productionMode;

    private final Map<String, PrivateKey> privateKeys = new ConcurrentHashMap<>();
    private final Map<String, PublicKey> publicKeys = new ConcurrentHashMap<>();

    public EntitlementSigningService(
        @Value("${winterarc.entitlement.active-key-id:ed25519-2026-v1}") String activeKeyId,
        @Value("${winterarc.entitlement.private-key:}") String configuredPrivateKey,
        @Value("${winterarc.entitlement.public-key:}") String configuredPublicKey,
        @Value("${winterarc.entitlement.product-id:winter-arc-desktop}") String productId,
        @Value("${winterarc.entitlement.schema-version:1}") int schemaVersion,
        @Value("${winterarc.entitlement.offline-grace-period-seconds:1209600}") long offlineGraceSeconds,
        @Value("${winterarc.entitlement.production-mode:false}") boolean productionMode) {
        this.activeKeyId = activeKeyId;
        this.configuredPrivateKey = configuredPrivateKey;
        this.configuredPublicKey = configuredPublicKey;
        this.productId = productId;
        this.schemaVersion = schemaVersion;
        this.offlineGraceSeconds = offlineGraceSeconds;
        this.productionMode = productionMode;
    }

    @PostConstruct
    public void init() {
        logger.info("Initializing Ed25519 Entitlement Signing Service (activeKeyId={})...", activeKeyId);

        String privKeyB64 = configuredPrivateKey != null ? configuredPrivateKey.trim() : "";
        String pubKeyB64 = configuredPublicKey != null ? configuredPublicKey.trim() : "";

        if (productionMode) {
            if (privKeyB64.isEmpty() || privKeyB64.equals(DEV_PRIVATE_KEY_B64)) {
                String error = "CRITICAL SECURITY FAILURE: Production mode is enabled, but 'winterarc.entitlement.private-key' "
                    + "is either missing or using the insecure default dev key! Aborting server startup.";
                logger.error(error);
                throw new IllegalStateException(error);
            }
        } else {
            if (privKeyB64.isEmpty()) {
                logger.warn("⚠️ [DEV MODE] No Ed25519 private key configured. Falling back to built-in local development key '{}'. NEVER USE IN PRODUCTION!", DEFAULT_DEV_KEY_ID);
                privKeyB64 = DEV_PRIVATE_KEY_B64;
                pubKeyB64 = DEV_PUBLIC_KEY_B64;
            }
        }

        try {
            PrivateKey activePriv = decodePrivateKey(privKeyB64);
            privateKeys.put(activeKeyId, activePriv);

            if (!pubKeyB64.isEmpty()) {
                PublicKey activePub = decodePublicKey(pubKeyB64);
                publicKeys.put(activeKeyId, activePub);
            }

            // Also register rotation test key if in dev mode
            if (!productionMode) {
                privateKeys.put(ROTATION_V2_KEY_ID, decodePrivateKey(ROTATION_V2_PRIVATE_KEY_B64));
                publicKeys.put(ROTATION_V2_KEY_ID, decodePublicKey(ROTATION_V2_PUBLIC_KEY_B64));
            }

            logger.info("Ed25519 Entitlement keys loaded successfully. Active signing keyId: {}", activeKeyId);
        } catch (Exception e) {
            logger.error("Failed to decode Ed25519 cryptographic keys", e);
            throw new IllegalStateException("Invalid Ed25519 key configuration: " + e.getMessage(), e);
        }
    }

    public static class SignedEntitlement extends EntitlementToken {
        public SignedEntitlement() { super(); }
        public SignedEntitlement(int schemaVersion, String productId, String tokenId, String keyId,
                                 String userId, String email, SubscriptionTier tier,
                                 long issuedAt, long expiresAt, long offlineGracePeriodSeconds,
                                 String signature) {
            super(schemaVersion, productId, tokenId, keyId, userId, email, tier, issuedAt, expiresAt, offlineGracePeriodSeconds, signature);
        }
        public SignedEntitlement(String userId, String email, SubscriptionTier tier,
                                 long issuedAt, long expiresAt, long offlineGracePeriodSeconds, String signature) {
            super(CURRENT_SCHEMA_VERSION, EXPECTED_PRODUCT_ID, UUID.randomUUID().toString(), DEFAULT_DEV_KEY_ID,
                  userId, email, tier, issuedAt, expiresAt, offlineGracePeriodSeconds, signature);
        }
    }

    /**
     * Issues an authoritative, Ed25519-signed entitlement token for the user.
     */
    public EntitlementToken generateEntitlement(User user, Subscription subscription) {
        return generateEntitlement(user, subscription, this.activeKeyId);
    }

    /**
     * Issues an authoritative, Ed25519-signed entitlement token with a specific keyId for rotation testing.
     */
    public EntitlementToken generateEntitlement(User user, Subscription subscription, String keyIdToUse) {
        long now = Instant.now().getEpochSecond();
        SubscriptionTier tier = SubscriptionTier.FREE;
        long expiresAt;

        if (subscription != null && subscription.isActive()) {
            tier = subscription.getTier();
            if (tier == SubscriptionTier.LIFETIME) {
                expiresAt = now + (100L * 365L * 86400L); // 100 years
            } else if (subscription.getCurrentPeriodEnd() != null) {
                expiresAt = subscription.getCurrentPeriodEnd().getEpochSecond();
            } else {
                expiresAt = now + (30L * 86400L);
            }
        } else {
            // Free tier or expired/cancelled/revoked subscription
            tier = SubscriptionTier.FREE;
            expiresAt = now + (365L * 86400L); // Free tier token renewed periodically
        }

        String tokenId = UUID.randomUUID().toString();
        EntitlementToken token = new EntitlementToken(
            schemaVersion,
            productId,
            tokenId,
            keyIdToUse,
            user.getId(),
            user.getEmail(),
            tier,
            now,
            expiresAt,
            offlineGraceSeconds,
            ""
        );

        String signature = sign(token.toCanonicalBytes(), keyIdToUse);
        token.setSignature(signature);
        return token;
    }

    public String sign(byte[] canonicalBytes, String keyId) {
        PrivateKey priv = privateKeys.get(keyId);
        if (priv == null) {
            throw new IllegalStateException("Signing key not found for keyId: " + keyId);
        }
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(priv);
            signature.update(canonicalBytes);
            byte[] signed = signature.sign();
            return Base64.getEncoder().encodeToString(signed);
        } catch (Exception e) {
            logger.error("Failed to sign payload using Ed25519 for keyId={}", keyId, e);
            throw new RuntimeException("Entitlement signing failed: " + e.getMessage(), e);
        }
    }

    public boolean verifySignature(EntitlementToken token) {
        if (token == null || token.getSignature() == null || token.getSignature().isBlank()) {
            return false;
        }

        PublicKey pub = publicKeys.get(token.getKeyId());
        if (pub == null) {
            logger.warn("Verification failed: Unknown or untrusted keyId={}", token.getKeyId());
            return false;
        }

        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(pub);
            verifier.update(token.toCanonicalBytes());
            byte[] sigBytes = Base64.getDecoder().decode(token.getSignature().trim());
            return verifier.verify(sigBytes);
        } catch (Exception e) {
            logger.error("Error during Ed25519 signature verification", e);
            return false;
        }
    }

    public void registerKey(String keyId, PrivateKey privateKey, PublicKey publicKey) {
        if (privateKey != null) privateKeys.put(keyId, privateKey);
        if (publicKey != null) publicKeys.put(keyId, publicKey);
    }

    public static PrivateKey decodePrivateKey(String base64Pkcs8) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(base64Pkcs8.trim());
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(bytes);
        KeyFactory kf = KeyFactory.getInstance("Ed25519");
        return kf.generatePrivate(spec);
    }

    public static PublicKey decodePublicKey(String base64X509) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(base64X509.trim());
        X509EncodedKeySpec spec = new X509EncodedKeySpec(bytes);
        KeyFactory kf = KeyFactory.getInstance("Ed25519");
        return kf.generatePublic(spec);
    }

    public String getActiveKeyId() { return activeKeyId; }
    public String getProductId() { return productId; }
    public int getSchemaVersion() { return schemaVersion; }
    public long getOfflineGraceSeconds() { return offlineGraceSeconds; }
}
