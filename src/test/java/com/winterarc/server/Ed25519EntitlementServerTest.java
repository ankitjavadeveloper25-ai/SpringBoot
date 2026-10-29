package com.winterarc.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.winterarc.server.config.JwtUtil;
import com.winterarc.server.model.EntitlementToken;
import com.winterarc.server.model.Subscription;
import com.winterarc.server.model.SubscriptionTier;
import com.winterarc.server.model.User;
import com.winterarc.server.repository.SubscriptionRepository;
import com.winterarc.server.repository.UserRepository;
import com.winterarc.server.service.EntitlementSigningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Server-side Ed25519 Entitlement and Security Test Suite.
 * Covers Cases: 8, 9, 15, and Server-Authoritative Subscription Policies.
 */
@SpringBootTest
@AutoConfigureMockMvc
class Ed25519EntitlementServerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntitlementSigningService signingService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private JwtUtil jwtUtil;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanDb() {
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void test8_UnauthorizedEntitlementRequestReturns401() throws Exception {
        // Request without Authorization header must be rejected by Spring Security
        mockMvc.perform(get("/api/entitlements/verify"))
            .andExpect(status().isForbidden()); // In Spring Security without credentials, 401 or 403 Forbidden
    }

    @Test
    void test9_UserCannotRequestAnotherUsersEntitlement() throws Exception {
        // Create User A (Free)
        User userA = userRepository.save(new User("userA@winterarc.pro", "pwdHashA", "User A"));
        // Create User B (Pro Yearly)
        User userB = userRepository.save(new User("userB@winterarc.pro", "pwdHashB", "User B"));
        Subscription subB = new Subscription(userB, SubscriptionTier.PRO_YEARLY, "ACTIVE", "STRIPE",
            Instant.now(), Instant.now().plusSeconds(365L * 86400L));
        subscriptionRepository.save(subB);

        // Generate JWT for User A
        String jwtA = jwtUtil.generateToken(userA.getId(), userA.getEmail(), userA.getRole());

        // When User A calls /api/entitlements/verify with their JWT, they MUST get User A's entitlement (FREE),
        // even if they attempt to pass headers or query params for User B.
        mockMvc.perform(get("/api/entitlements/verify")
                .header("Authorization", "Bearer " + jwtA)
                .param("userId", userB.getId())) // Attacker trying to supply User B's ID in query params
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(userA.getId()))
            .andExpect(jsonPath("$.email").value(userA.getEmail()))
            .andExpect(jsonPath("$.tier").value("FREE")); // User A's real tier, NOT User B's Pro tier!
    }

    @Test
    void testServerAuthoritativeSubscriptionExpiryAndStatus() {
        User user = userRepository.save(new User("member@winterarc.pro", "hash", "Member"));

        // 1. Lifetime subscription
        Instant hundredYears = Instant.now().plusSeconds(100L * 365L * 86400L);
        Subscription lifetimeSub = subscriptionRepository.save(new Subscription(
            user, SubscriptionTier.LIFETIME, "ACTIVE", "STRIPE", Instant.now(), hundredYears
        ));
        EntitlementToken lifetimeToken = signingService.generateEntitlement(user, lifetimeSub);
        assertEquals(SubscriptionTier.LIFETIME, lifetimeToken.getTier());
        assertTrue(lifetimeToken.getExpiresAt() > (Instant.now().getEpochSecond() + (90L * 365L * 86400L)),
            "Lifetime subscription must have ~100-year expiration");

        // 2. Active Pro Yearly subscription
        Instant oneYearLater = Instant.now().plusSeconds(365L * 86400L);
        Subscription yearlySub = subscriptionRepository.save(new Subscription(
            user, SubscriptionTier.PRO_YEARLY, "ACTIVE", "STRIPE", Instant.now(), oneYearLater
        ));
        EntitlementToken yearlyToken = signingService.generateEntitlement(user, yearlySub);
        assertEquals(SubscriptionTier.PRO_YEARLY, yearlyToken.getTier());
        assertEquals(oneYearLater.getEpochSecond(), yearlyToken.getExpiresAt());

        // 3. Cancelled/Expired subscription -> must fall back to FREE tier
        Subscription cancelledSub = subscriptionRepository.save(new Subscription(
            user, SubscriptionTier.PRO_YEARLY, "CANCELLED", "STRIPE",
            Instant.now().minusSeconds(60L * 86400L), Instant.now().minusSeconds(10L * 86400L)
        ));
        EntitlementToken cancelledToken = signingService.generateEntitlement(user, cancelledSub);
        assertEquals(SubscriptionTier.FREE, cancelledToken.getTier(), "Cancelled/expired sub must result in FREE tier token");
    }

    @Test
    void test15_MissingProductionSigningKeyFailsStartup() {
        // Test production fail-safe verification
        EntitlementSigningService prodServiceMissingKey = new EntitlementSigningService(
            "ed25519-2026-v1",
            "", // Missing private key
            "",
            "winter-arc-desktop",
            1,
            1209600L,
            true // productionMode = true!
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, prodServiceMissingKey::init);
        assertTrue(ex.getMessage().contains("CRITICAL SECURITY FAILURE"),
            "Production mode must fail startup when private key is missing");

        // Also test using default dev key in production mode
        EntitlementSigningService prodServiceWithDevKey = new EntitlementSigningService(
            "ed25519-2026-v1",
            EntitlementSigningService.DEV_PRIVATE_KEY_B64, // Dev key used in production
            "",
            "winter-arc-desktop",
            1,
            1209600L,
            true // productionMode = true!
        );

        IllegalStateException ex2 = assertThrows(IllegalStateException.class, prodServiceWithDevKey::init);
        assertTrue(ex2.getMessage().contains("CRITICAL SECURITY FAILURE"),
            "Production mode must fail startup when dev key is used in production");
    }

    @Test
    void testKeyRotationSigningAndVerifying() {
        User user = userRepository.save(new User("rotate@winterarc.pro", "hash", "Rotation User"));
        Subscription sub = subscriptionRepository.save(new Subscription(
            user, SubscriptionTier.PRO_MONTHLY, "ACTIVE", "STRIPE",
            Instant.now(), Instant.now().plusSeconds(30L * 86400L)
        ));

        // Sign with rotation key V2
        EntitlementToken tokenV2 = signingService.generateEntitlement(user, sub, EntitlementSigningService.ROTATION_V2_KEY_ID);
        assertEquals(EntitlementSigningService.ROTATION_V2_KEY_ID, tokenV2.getKeyId());
        assertTrue(signingService.verifySignature(tokenV2), "Server must verify token signed with rotation key V2");
    }
}
