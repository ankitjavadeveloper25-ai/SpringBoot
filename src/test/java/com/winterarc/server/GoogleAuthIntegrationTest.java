package com.winterarc.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.winterarc.server.model.Subscription;
import com.winterarc.server.model.SubscriptionTier;
import com.winterarc.server.model.User;
import com.winterarc.server.repository.SubscriptionRepository;
import com.winterarc.server.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class GoogleAuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Google Login: New user is automatically registered with Free subscription and receives JWT")
    void testGoogleLoginNewUser() throws Exception {
        String testEmail = "google.test.user" + System.currentTimeMillis() + "@gmail.com";
        String mockToken = "mock-google-" + testEmail;

        Map<String, String> payload = Map.of("credential", mockToken);

        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.email").value(testEmail.toLowerCase()))
                .andExpect(jsonPath("$.role").value("ROLE_USER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.isNewUser").value(true));

        // Verify database persistence
        Optional<User> userOpt = userRepository.findByEmail(testEmail.toLowerCase());
        assertThat(userOpt).isPresent();
        User user = userOpt.get();
        assertThat(user.getRole()).isEqualTo("ROLE_USER");

        List<Subscription> subs = subscriptionRepository.findByUserOrderByCreatedAtDesc(user);
        assertThat(subs).isNotEmpty();
        assertThat(subs.get(0).getTier()).isEqualTo(SubscriptionTier.FREE);
        assertThat(subs.get(0).getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("Google Login: Existing user successfully logs in and returns existing user record")
    void testGoogleLoginExistingUser() throws Exception {
        String testEmail = "google.existing" + System.currentTimeMillis() + "@gmail.com";
        String mockToken = "mock-google-" + testEmail;

        // First login -> creates user
        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("credential", mockToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isNewUser").value(true));

        // Second login -> existing user
        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("credential", mockToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(testEmail.toLowerCase()))
                .andExpect(jsonPath("$.isNewUser").value(false));
    }

    @Test
    @DisplayName("Google Login: Suspended user is rejected with 403 Forbidden")
    void testGoogleLoginSuspendedUser() throws Exception {
        String testEmail = "google.suspended" + System.currentTimeMillis() + "@gmail.com";
        String mockToken = "mock-google-" + testEmail;

        // Register user
        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("credential", mockToken))))
                .andExpect(status().isOk());

        // Suspend user
        User user = userRepository.findByEmail(testEmail.toLowerCase()).orElseThrow();
        user.setStatus("SUSPENDED");
        userRepository.save(user);

        // Attempt login again
        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("credential", mockToken))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Your account has been suspended. Please contact support."));
    }

    @Test
    @DisplayName("Google Login: Missing credential yields 400 Bad Request")
    void testGoogleLoginMissingCredential() throws Exception {
        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
}
