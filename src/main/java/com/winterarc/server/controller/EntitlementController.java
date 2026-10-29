package com.winterarc.server.controller;

import com.winterarc.server.model.EntitlementToken;
import com.winterarc.server.model.Subscription;
import com.winterarc.server.model.User;
import com.winterarc.server.repository.SubscriptionRepository;
import com.winterarc.server.repository.UserRepository;
import com.winterarc.server.service.EntitlementSigningService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/entitlements")
public class EntitlementController {

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final EntitlementSigningService signingService;

    public EntitlementController(UserRepository userRepository,
                                 SubscriptionRepository subscriptionRepository,
                                 EntitlementSigningService signingService) {
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.signingService = signingService;
    }

    @GetMapping("/verify")
    public ResponseEntity<?> getSignedEntitlement(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        // Strict server-side identity: User ID is retrieved strictly from the verified JWT principal
        String userId = (String) auth.getPrincipal();
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        }

        User user = userOpt.get();
        Optional<Subscription> subOpt = subscriptionRepository.findTopByUserOrderByCreatedAtDesc(user);
        Subscription subscription = subOpt.orElse(null);

        EntitlementToken signed = signingService.generateEntitlement(user, subscription);
        return ResponseEntity.ok(signed);
    }

    @GetMapping("/status")
    public ResponseEntity<?> getStatus(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String userId = (String) auth.getPrincipal();
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        }

        User user = userOpt.get();
        Optional<Subscription> subOpt = subscriptionRepository.findTopByUserOrderByCreatedAtDesc(user);
        Subscription sub = subOpt.orElse(null);

        return ResponseEntity.ok(Map.of(
            "userId", user.getId(),
            "email", user.getEmail(),
            "tier", sub != null ? sub.getTier().name() : "FREE",
            "active", sub != null && sub.isActive(),
            "expiresAt", sub != null && sub.getCurrentPeriodEnd() != null ? sub.getCurrentPeriodEnd().toString() : "N/A"
        ));
    }
}
