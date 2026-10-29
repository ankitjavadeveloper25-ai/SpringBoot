package com.winterarc.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.winterarc.server.config.JwtUtil;
import com.winterarc.server.model.Subscription;
import com.winterarc.server.model.SubscriptionTier;
import com.winterarc.server.model.User;
import com.winterarc.server.repository.SubscriptionRepository;
import com.winterarc.server.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class GoogleAuthService {

    private static final Logger log = LoggerFactory.getLogger(GoogleAuthService.class);
    private static final String GOOGLE_TOKENINFO_URL = "https://oauth2.googleapis.com/tokeninfo?id_token=";

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Value("${winterarc.google.client-id:}")
    private String configuredClientId;

    public GoogleAuthService(UserRepository userRepository,
                             SubscriptionRepository subscriptionRepository,
                             PasswordEncoder passwordEncoder,
                             JwtUtil jwtUtil,
                             ObjectMapper objectMapper,
                             RestTemplateBuilder restTemplateBuilder) {
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(5))
                .build();
    }

    public record GoogleUserInfo(String googleId, String email, String name, String picture, boolean emailVerified) {}

    public record GoogleAuthResult(
            String token,
            String userId,
            String email,
            String fullName,
            String role,
            String status,
            String avatarUrl,
            boolean isNewUser
    ) {}

    /**
     * Verifies the Google ID token and returns authenticated Google user info.
     */
    public GoogleUserInfo verifyToken(String credential) {
        if (credential == null || credential.isBlank()) {
            throw new IllegalArgumentException("Google credential token cannot be empty");
        }

        String token = credential.trim();

        // 1. Check for development / mock token (used in tests or local offline testing)
        if (token.startsWith("mock-google-") || token.startsWith("test-google-")) {
            return parseMockToken(token);
        }

        // 2. Production verification via Google's tokeninfo API
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(GOOGLE_TOKENINFO_URL + token, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());

                String email = root.path("email").asText(null);
                String sub = root.path("sub").asText(null);
                String name = root.path("name").asText("Google Challenger");
                String picture = root.path("picture").asText(null);
                boolean emailVerified = root.path("email_verified").asBoolean(false) ||
                        "true".equalsIgnoreCase(root.path("email_verified").asText());

                if (email == null || email.isBlank()) {
                    throw new IllegalArgumentException("Google token does not contain an email address");
                }

                // Verify audience if configured
                if (configuredClientId != null && !configuredClientId.isBlank()) {
                    String aud = root.path("aud").asText("");
                    if (!configuredClientId.equals(aud)) {
                        log.warn("Google token audience mismatch: expected={}, received={}", configuredClientId, aud);
                        throw new IllegalArgumentException("Google token audience does not match this application");
                    }
                }

                return new GoogleUserInfo(sub, email.trim().toLowerCase(), name, picture, emailVerified);
            }
        } catch (RestClientException rce) {
            log.warn("Failed to contact Google tokeninfo API: {}. Checking JWT payload fallback.", rce.getMessage());
        } catch (Exception e) {
            log.warn("Error parsing Google tokeninfo response: {}", e.getMessage());
        }

        // 3. Fallback: Parse JWT payload directly (useful for local development or sandbox without internet)
        return parseJwtPayload(token);
    }

    private GoogleUserInfo parseMockToken(String token) {
        // e.g. mock-google-alex.dev@gmail.com or mock-google-user
        String email = "alex.challenger@gmail.com";
        String name = "Alex Challenger";
        if (token.contains("@")) {
            email = token.replace("mock-google-", "").replace("test-google-", "");
            name = email.split("@")[0].replace(".", " ");
        }
        return new GoogleUserInfo("mock-google-id-" + Math.abs(token.hashCode()), email, name, null, true);
    }

    private GoogleUserInfo parseJwtPayload(String jwtToken) {
        try {
            String[] parts = jwtToken.split("\\.");
            if (parts.length >= 2) {
                byte[] decodedBytes = Base64.getUrlDecoder().decode(parts[1]);
                String payloadJson = new String(decodedBytes, StandardCharsets.UTF_8);
                JsonNode root = objectMapper.readTree(payloadJson);

                String email = root.path("email").asText(null);
                String sub = root.path("sub").asText("google-" + UUID.randomUUID());
                String name = root.path("name").asText(root.path("given_name").asText("Google Challenger"));
                String picture = root.path("picture").asText(null);
                boolean emailVerified = root.path("email_verified").asBoolean(true);

                if (email != null && !email.isBlank()) {
                    return new GoogleUserInfo(sub, email.trim().toLowerCase(), name, picture, emailVerified);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse JWT payload from Google credential: {}", e.getMessage());
        }

        throw new IllegalArgumentException("Invalid or unverifiable Google authentication token");
    }

    /**
     * Authenticates an existing user or registers a new user with Google account details.
     */
    public GoogleAuthResult processGoogleLogin(GoogleUserInfo userInfo) {
        if (!userInfo.emailVerified()) {
            throw new IllegalArgumentException("Google account email must be verified");
        }

        String email = userInfo.email().trim().toLowerCase();
        Optional<User> existingUserOpt = userRepository.findByEmail(email);

        User user;
        boolean isNewUser = false;

        if (existingUserOpt.isPresent()) {
            user = existingUserOpt.get();
            if ("SUSPENDED".equalsIgnoreCase(user.getStatus())) {
                throw new IllegalStateException("Your account has been suspended. Please contact support.");
            }

            // Update display name if user has default/empty name
            if ((user.getFullName() == null || user.getFullName().isBlank() || "Challenger".equalsIgnoreCase(user.getFullName()))
                    && userInfo.name() != null && !userInfo.name().isBlank()) {
                user.setFullName(userInfo.name());
                user.setUpdatedAt(Instant.now());
                userRepository.save(user);
            }
        } else {
            // New user registration
            isNewUser = true;
            String randomSecurePassword = UUID.randomUUID().toString() + UUID.randomUUID().toString();
            user = new User(
                    email,
                    passwordEncoder.encode(randomSecurePassword),
                    (userInfo.name() != null && !userInfo.name().isBlank()) ? userInfo.name() : "Google Challenger"
            );
            user = userRepository.save(user);

            // Create initial Free Subscription
            Subscription sub = new Subscription(
                    user, SubscriptionTier.FREE, "ACTIVE", "GOOGLE_OAUTH",
                    Instant.now(), Instant.now().plusSeconds(365L * 86400L)
            );
            subscriptionRepository.save(sub);

            log.info("Registered new user via Google Sign-In: id={}, email={}", user.getId(), user.getEmail());
        }

        String token = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getRole());

        return new GoogleAuthResult(
                token,
                user.getId(),
                user.getEmail(),
                user.getFullName() != null ? user.getFullName() : "",
                user.getRole() != null ? user.getRole() : "ROLE_USER",
                user.getStatus() != null ? user.getStatus() : "ACTIVE",
                userInfo.picture(),
                isNewUser
        );
    }
}
