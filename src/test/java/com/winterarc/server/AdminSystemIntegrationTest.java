package com.winterarc.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.winterarc.server.config.JwtUtil;
import com.winterarc.server.model.*;
import com.winterarc.server.repository.AdminAuditLogRepository;
import com.winterarc.server.repository.SubscriptionGrantRepository;
import com.winterarc.server.repository.SubscriptionRepository;
import com.winterarc.server.repository.UserRepository;
import com.winterarc.server.service.AdminSubscriptionService;
import com.winterarc.server.service.AdminUserService;
import com.winterarc.server.service.EntitlementSigningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AdminSystemIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private SubscriptionGrantRepository grantRepository;

    @Autowired
    private AdminAuditLogRepository auditLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private EntitlementSigningService signingService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User adminUser;
    private User regularUser;
    private String adminToken;
    private String regularUserToken;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        grantRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();

        // Create Admin
        adminUser = new User("admin@winterarc.pro", passwordEncoder.encode("AdminPass123!"), "System Admin");
        adminUser.setRole("ROLE_ADMIN");
        adminUser.setStatus("ACTIVE");
        adminUser = userRepository.save(adminUser);
        adminToken = jwtUtil.generateToken(adminUser.getId(), adminUser.getEmail(), adminUser.getRole());

        // Create Regular User
        regularUser = new User("challenger@winterarc.pro", passwordEncoder.encode("UserPass123!"), "Test User");
        regularUser.setRole("ROLE_USER");
        regularUser.setStatus("ACTIVE");
        regularUser = userRepository.save(regularUser);
        regularUserToken = jwtUtil.generateToken(regularUser.getId(), regularUser.getEmail(), regularUser.getRole());

        // Default Free Subscription for regular user
        Subscription freeSub = new Subscription(
                regularUser, SubscriptionTier.FREE, "ACTIVE", "MANUAL",
                Instant.now(), Instant.now().plusSeconds(365L * 86400L)
        );
        subscriptionRepository.save(freeSub);
    }

    @Test
    void testOrdinaryUserCannotAccessAdminEndpoints() throws Exception {
        // Regular user should receive 403 Forbidden
        mockMvc.perform(get("/api/admin/dashboard/stats")
                .header("Authorization", "Bearer " + regularUserToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/users")
                .header("Authorization", "Bearer " + regularUserToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void testAdminCanAccessDashboardStats() throws Exception {
        mockMvc.perform(get("/api/admin/dashboard/stats")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsers").value(2))
                .andExpect(jsonPath("$.activeUsers").value(2))
                .andExpect(jsonPath("$.freeSubscribers").value(1));
    }

    @Test
    void testGrantComplimentarySubscriptionAndVerifyEd25519Token() throws Exception {
        AdminSubscriptionService.GrantRequest grantRequest = new AdminSubscriptionService.GrantRequest(
                SubscriptionTier.PRO_YEARLY,
                365,
                null,
                "COMPLIMENTARY",
                "Community excellence award for open-source contributions"
        );

        // 1. Admin grants complimentary subscription
        mockMvc.perform(post("/api/admin/users/" + regularUser.getId() + "/complimentary-subscriptions")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(grantRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Complimentary Pro subscription granted successfully"))
                .andExpect(jsonPath("$.grant.tier").value("PRO_YEARLY"))
                .andExpect(jsonPath("$.grant.grantType").value("COMPLIMENTARY"));

        // 2. Verify subscription in database is updated
        Subscription updatedSub = subscriptionRepository.findTopByUserOrderByCreatedAtDesc(regularUser).orElseThrow();
        assertEquals(SubscriptionTier.PRO_YEARLY, updatedSub.getTier());
        assertEquals("ACTIVE", updatedSub.getStatus());
        assertEquals("COMPLIMENTARY", updatedSub.getPaymentProvider());

        // 3. Verify audit log entry was created
        List<AdminAuditLog> logs = auditLogRepository.findAll();
        assertEquals(1, logs.size());
        AdminAuditLog log = logs.get(0);
        assertEquals("GRANT_COMPLIMENTARY", log.getAction());
        assertEquals(adminUser.getEmail(), log.getAdminEmail());
        assertEquals(regularUser.getId(), log.getTargetUserId());
        assertEquals("Community excellence award for open-source contributions", log.getReason());

        // 4. Critical: Verify that calling /api/entitlements/verify as the regular user
        // produces a valid, cryptographically signed Ed25519 entitlement token for PRO_YEARLY!
        String entitlementResponse = mockMvc.perform(get("/api/entitlements/verify")
                .header("Authorization", "Bearer " + regularUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("PRO_YEARLY"))
                .andExpect(jsonPath("$.userId").value(regularUser.getId()))
                .andReturn().getResponse().getContentAsString();

        EntitlementToken token = objectMapper.readValue(entitlementResponse, EntitlementToken.class);
        assertNotNull(token.getSignature(), "Token must contain cryptographic Ed25519 signature");
        assertTrue(signingService.verifySignature(token), "Ed25519 signature verification must succeed");
    }

    @Test
    void testExtendAndRevokeComplimentarySubscription() throws Exception {
        // First grant a 30-day Pro subscription
        AdminSubscriptionService.GrantRequest grantRequest = new AdminSubscriptionService.GrantRequest(
                SubscriptionTier.PRO_MONTHLY, 30, null, "COMPLIMENTARY", "Initial 30-day grant"
        );
        mockMvc.perform(post("/api/admin/users/" + regularUser.getId() + "/complimentary-subscriptions")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(grantRequest)))
                .andExpect(status().isOk());

        Subscription subBeforeExtend = subscriptionRepository.findTopByUserOrderByCreatedAtDesc(regularUser).orElseThrow();
        Instant expiryBefore = subBeforeExtend.getCurrentPeriodEnd();

        // Extend by 15 days
        AdminSubscriptionService.ExtendRequest extendReq = new AdminSubscriptionService.ExtendRequest(
                15, "Bonus extension for hackathon winner"
        );
        mockMvc.perform(post("/api/admin/users/" + regularUser.getId() + "/subscriptions/extend")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(extendReq)))
                .andExpect(status().isOk());

        Subscription subAfterExtend = subscriptionRepository.findTopByUserOrderByCreatedAtDesc(regularUser).orElseThrow();
        assertTrue(subAfterExtend.getCurrentPeriodEnd().isAfter(expiryBefore));

        // Revoke the subscription
        AdminSubscriptionService.RevokeRequest revokeReq = new AdminSubscriptionService.RevokeRequest(
                "User requested account rollback"
        );
        mockMvc.perform(post("/api/admin/users/" + regularUser.getId() + "/subscriptions/revoke")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(revokeReq)))
                .andExpect(status().isOk());

        Subscription subAfterRevoke = subscriptionRepository.findTopByUserOrderByCreatedAtDesc(regularUser).orElseThrow();
        assertEquals("CANCELLED", subAfterRevoke.getStatus());

        // Verifying entitlements now falls back to FREE tier
        mockMvc.perform(get("/api/entitlements/verify")
                .header("Authorization", "Bearer " + regularUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("FREE"));
    }

    @Test
    void testAdminCannotSuspendSelf() throws Exception {
        AdminUserService.StatusUpdateRequest suspendReq = new AdminUserService.StatusUpdateRequest(
                "SUSPENDED", "Self suspension attempt"
        );

        mockMvc.perform(post("/api/admin/users/" + adminUser.getId() + "/status")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(suspendReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Administrators cannot suspend their own account."));
    }

    @Test
    void testSuspendedUserBlockedFromLogin() throws Exception {
        // Suspend regular user
        AdminUserService.StatusUpdateRequest suspendReq = new AdminUserService.StatusUpdateRequest(
                "SUSPENDED", "Suspicious spam activity detected"
        );
        mockMvc.perform(post("/api/admin/users/" + regularUser.getId() + "/status")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(suspendReq)))
                .andExpect(status().isOk());

        // Attempt login with suspended account
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"challenger@winterarc.pro\",\"password\":\"UserPass123!\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Account has been suspended. Please contact administration."));
    }

    @Test
    void testGrantFailsWithoutReason() throws Exception {
        AdminSubscriptionService.GrantRequest invalidReq = new AdminSubscriptionService.GrantRequest(
                SubscriptionTier.PRO_MONTHLY, 30, null, "COMPLIMENTARY", ""
        );

        mockMvc.perform(post("/api/admin/users/" + regularUser.getId() + "/complimentary-subscriptions")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void testChangeAdminPassword() throws Exception {
        AdminUserService.ChangePasswordRequest req = new AdminUserService.ChangePasswordRequest(
                "AdminPass123!", "NewSecretAdmin2026@!", "NewSecretAdmin2026@!"
        );

        mockMvc.perform(post("/api/admin/users/profile/change-password")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Administrator password successfully updated."));

        // Verify can login with new password
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin@winterarc.pro\",\"password\":\"NewSecretAdmin2026@!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists());
    }

    @Test
    void testExportUsersCsv() throws Exception {
        mockMvc.perform(get("/api/admin/users/export/csv")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"winterarc-users.csv\""))
                .andExpect(content().contentType("text/csv"));
    }

    @Test
    void testAnnouncementLifecycle() throws Exception {
        // Create announcement
        String reqJson = "{\"title\":\"System Update\",\"message\":\"Server maintenance at 2 AM UTC\",\"type\":\"MAINTENANCE\",\"durationDays\":7}";
        mockMvc.perform(post("/api/admin/announcements")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(reqJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("System Update"));

        // Public desktop endpoint can read active announcements
        mockMvc.perform(get("/api/announcements/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("System Update"));
    }

    @Test
    void testPromoCodeLifecycle() throws Exception {
        String promoJson = "{\"code\":\"LAUNCH2026\",\"discountPercent\":25,\"maxRedemptions\":50,\"validDays\":30}";
        mockMvc.perform(post("/api/admin/promo-codes")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(promoJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("LAUNCH2026"))
                .andExpect(jsonPath("$.discountPercent").value(25));
    }
}
