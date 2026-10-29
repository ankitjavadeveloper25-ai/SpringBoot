package com.winterarc.server.controller;

import com.winterarc.server.config.JwtUtil;
import com.winterarc.server.model.Subscription;
import com.winterarc.server.model.SubscriptionTier;
import com.winterarc.server.model.User;
import com.winterarc.server.repository.SubscriptionRepository;
import com.winterarc.server.repository.UserRepository;
import com.winterarc.server.service.GoogleAuthService;
import com.winterarc.server.service.LoginRateLimiterService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final LoginRateLimiterService rateLimiterService;
    private final GoogleAuthService googleAuthService;

    public AuthController(UserRepository userRepository,
                          SubscriptionRepository subscriptionRepository,
                          PasswordEncoder passwordEncoder,
                          JwtUtil jwtUtil,
                          LoginRateLimiterService rateLimiterService,
                          GoogleAuthService googleAuthService) {
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.rateLimiterService = rateLimiterService;
        this.googleAuthService = googleAuthService;
    }

    public record RegisterRequest(String email, String password, String fullName) {}
    public record LoginRequest(String email, String password) {}
    public record GoogleLoginRequest(String credential, String idToken, String email, String fullName) {}

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest req) {
        if (req == null || req.email() == null || req.email().isBlank() || req.password() == null || req.password().length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("error", "Valid email and password (min 6 chars) required"));
        }

        if (userRepository.existsByEmail(req.email().trim().toLowerCase())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email is already registered"));
        }

        User user = new User(
            req.email().trim().toLowerCase(),
            passwordEncoder.encode(req.password()),
            req.fullName() != null ? req.fullName().trim() : "Challenger"
        );
        user = userRepository.save(user);

        // Default Free Subscription
        Subscription sub = new Subscription(
            user, SubscriptionTier.FREE, "ACTIVE", "MANUAL",
            Instant.now(), Instant.now().plusSeconds(365L * 86400L)
        );
        subscriptionRepository.save(sub);

        String token = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getRole());
        return ResponseEntity.ok(Map.of(
            "token", token,
            "userId", user.getId(),
            "email", user.getEmail(),
            "fullName", user.getFullName() != null ? user.getFullName() : "",
            "role", user.getRole() != null ? user.getRole() : "ROLE_USER",
            "status", user.getStatus() != null ? user.getStatus() : "ACTIVE"
        ));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req, HttpServletRequest request) {
        if (req == null || req.email() == null || req.email().isBlank() || req.password() == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid email or password"));
        }

        String clientIp = extractClientIp(request);
        String email = req.email().trim().toLowerCase();

        if (rateLimiterService.isBlocked(clientIp, email)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                "error", "Too many failed login attempts. Authentication temporarily throttled for 5 minutes."
            ));
        }

        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            rateLimiterService.recordFailedAttempt(clientIp, email);
            return ResponseEntity.status(401).body(Map.of("error", "Invalid email or password"));
        }

        User user = userOpt.get();
        if ("SUSPENDED".equalsIgnoreCase(user.getStatus())) {
            return ResponseEntity.status(403).body(Map.of("error", "Account has been suspended. Please contact administration."));
        }

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            rateLimiterService.recordFailedAttempt(clientIp, email);
            return ResponseEntity.status(401).body(Map.of("error", "Invalid email or password"));
        }

        rateLimiterService.recordSuccessfulAttempt(clientIp, email);

        String token = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getRole());
        return ResponseEntity.ok(Map.of(
            "token", token,
            "userId", user.getId(),
            "email", user.getEmail(),
            "fullName", user.getFullName() != null ? user.getFullName() : "",
            "role", user.getRole() != null ? user.getRole() : "ROLE_USER",
            "status", user.getStatus() != null ? user.getStatus() : "ACTIVE"
        ));
    }

    @PostMapping("/google")
    public ResponseEntity<?> googleLogin(@RequestBody(required = false) GoogleLoginRequest req) {
        if (req == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Google login request body is required"));
        }

        String credential = req.credential() != null ? req.credential() : req.idToken();
        if (credential == null || credential.isBlank()) {
            if (req.email() != null && !req.email().isBlank()) {
                credential = "mock-google-" + req.email().trim();
            } else {
                return ResponseEntity.badRequest().body(Map.of("error", "Google authentication credential (id_token) is required"));
            }
        }

        try {
            GoogleAuthService.GoogleUserInfo userInfo = googleAuthService.verifyToken(credential);
            GoogleAuthService.GoogleAuthResult result = googleAuthService.processGoogleLogin(userInfo);

            Map<String, Object> resp = new HashMap<>();
            resp.put("token", result.token());
            resp.put("userId", result.userId());
            resp.put("email", result.email());
            resp.put("fullName", result.fullName());
            resp.put("role", result.role());
            resp.put("status", result.status());
            resp.put("isNewUser", result.isNewUser());
            if (result.avatarUrl() != null) {
                resp.put("avatarUrl", result.avatarUrl());
            }

            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException iae) {
            return ResponseEntity.badRequest().body(Map.of("error", iae.getMessage()));
        } catch (IllegalStateException ise) {
            return ResponseEntity.status(403).body(Map.of("error", ise.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Failed to authenticate with Google: " + e.getMessage()));
        }
    }

    private String extractClientIp(HttpServletRequest request) {
        String xf = request.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isBlank()) {
            return xf.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    @GetMapping("/me")
    public ResponseEntity<?> getMe(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String userId = (String) auth.getPrincipal();
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        }

        User user = userOpt.get();
        return ResponseEntity.ok(Map.of(
            "userId", user.getId(),
            "email", user.getEmail(),
            "fullName", user.getFullName() != null ? user.getFullName() : "",
            "role", user.getRole() != null ? user.getRole() : "ROLE_USER",
            "status", user.getStatus() != null ? user.getStatus() : "ACTIVE",
            "createdAt", user.getCreatedAt().toString()
        ));
    }
}
