package com.winterarc.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.winterarc.server.config.JwtUtil;
import com.winterarc.server.model.Subscription;
import com.winterarc.server.model.SubscriptionTier;
import com.winterarc.server.model.User;
import com.winterarc.server.repository.SubscriptionRepository;
import com.winterarc.server.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class UserProfileTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    private User testUser;
    private String userToken;

    @BeforeEach
    void setUp() {
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();

        testUser = new User("challenger@winterarc.pro", passwordEncoder.encode("Password123!"), "Ezio Auditore");
        testUser.setRole("ROLE_USER");
        testUser.setStatus("ACTIVE");
        testUser = userRepository.save(testUser);

        userToken = jwtUtil.generateToken(testUser.getId(), testUser.getEmail(), testUser.getRole());

        // Pro Yearly subscription
        Subscription sub = new Subscription(
                testUser, SubscriptionTier.PRO_YEARLY, "ACTIVE", "STRIPE",
                Instant.now(), Instant.now().plusSeconds(365L * 86400L)
        );
        subscriptionRepository.save(sub);
    }

    @Test
    void testGetProfileAuthenticated() throws Exception {
        mockMvc.perform(get("/api/profile")
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("challenger@winterarc.pro"))
                .andExpect(jsonPath("$.fullName").value("Ezio Auditore"))
                .andExpect(jsonPath("$.startingDate").exists())
                .andExpect(jsonPath("$.subscription.tier").value("PRO_YEARLY"))
                .andExpect(jsonPath("$.subscription.isPro").value(true))
                .andExpect(jsonPath("$.achievements").isArray())
                .andExpect(jsonPath("$.achievements[0].title").value("First Step Taken"))
                .andExpect(jsonPath("$.timeline").isArray());
    }

    @Test
    void testGetProfileUnauthenticatedFails() throws Exception {
        mockMvc.perform(get("/api/profile"))
                .andExpect(status().isForbidden());
    }

    @Test
    void testUpdateProfileName() throws Exception {
        String updateJson = "{\"fullName\":\"Master Assassin\"}";

        mockMvc.perform(put("/api/profile")
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Master Assassin"));
    }
}
