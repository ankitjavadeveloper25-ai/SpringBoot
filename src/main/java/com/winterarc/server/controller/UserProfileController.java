package com.winterarc.server.controller;

import com.winterarc.server.model.*;
import com.winterarc.server.repository.PaymentTransactionRepository;
import com.winterarc.server.repository.SubscriptionGrantRepository;
import com.winterarc.server.repository.SubscriptionRepository;
import com.winterarc.server.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/profile")
public class UserProfileController {

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PaymentTransactionRepository paymentRepository;
    private final SubscriptionGrantRepository grantRepository;
    private final PasswordEncoder passwordEncoder;

    public UserProfileController(UserRepository userRepository,
                                 SubscriptionRepository subscriptionRepository,
                                 PaymentTransactionRepository paymentRepository,
                                 SubscriptionGrantRepository grantRepository,
                                 PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.paymentRepository = paymentRepository;
        this.grantRepository = grantRepository;
        this.passwordEncoder = passwordEncoder;
    }

    private User getAuthenticatedUser(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) {
            throw new IllegalStateException("Authentication token required");
        }
        String userId = (String) auth.getPrincipal();
        return userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("User account not found: " + userId));
    }

    public record UpdateProfileRequest(String fullName, String currentPassword, String newPassword) {}

    public record UserAchievementDto(
            String id,
            String code,
            String title,
            String description,
            String icon,
            int points,
            boolean unlocked,
            String unlockedAt
    ) {}

    @GetMapping
    public ResponseEntity<Map<String, Object>> getProfile(Authentication auth) {
        User user = getAuthenticatedUser(auth);

        Instant startInstant = user.getCreatedAt() != null ? user.getCreatedAt() : Instant.now();
        long daysSinceStart = Math.max(1, Duration.between(startInstant, Instant.now()).toDays());

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("MMMM d, yyyy").withZone(ZoneId.of("UTC"));
        String startingDateFormatted = dtf.format(startInstant);

        // Subscription overview
        Subscription activeSub = subscriptionRepository.findTopByUserOrderByCreatedAtDesc(user).orElse(null);
        Map<String, Object> subMap = new LinkedHashMap<>();
        if (activeSub != null) {
            boolean isLifetime = activeSub.getTier() == SubscriptionTier.LIFETIME;
            long daysRemaining = isLifetime ? -1 : Math.max(0, Duration.between(Instant.now(), activeSub.getCurrentPeriodEnd()).toDays());
            subMap.put("id", activeSub.getId());
            subMap.put("tier", activeSub.getTier().name());
            subMap.put("tierDisplayName", activeSub.getTier().getDisplayName());
            subMap.put("status", activeSub.getStatus());
            subMap.put("provider", activeSub.getPaymentProvider());
            subMap.put("currentPeriodStart", activeSub.getCurrentPeriodStart().toString());
            subMap.put("currentPeriodEnd", activeSub.getCurrentPeriodEnd().toString());
            subMap.put("daysRemaining", daysRemaining);
            subMap.put("isLifetime", isLifetime);
            subMap.put("isPro", activeSub.getTier() != SubscriptionTier.FREE && "ACTIVE".equalsIgnoreCase(activeSub.getStatus()));
        } else {
            subMap.put("tier", "FREE");
            subMap.put("tierDisplayName", "Free Edition");
            subMap.put("status", "ACTIVE");
            subMap.put("provider", "MANUAL");
            subMap.put("daysRemaining", -1);
            subMap.put("isLifetime", false);
            subMap.put("isPro", false);
        }

        // Payments History
        List<PaymentTransaction> payments = paymentRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        List<Map<String, Object>> paymentDtos = new ArrayList<>();
        long totalPaidCents = 0;
        for (PaymentTransaction p : payments) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("id", p.getId());
            pm.put("provider", p.getProvider());
            pm.put("eventId", p.getEventId());
            pm.put("eventType", p.getEventType());
            pm.put("amountCents", p.getAmountCents());
            pm.put("currency", p.getCurrency());
            pm.put("status", p.getStatus());
            pm.put("createdAt", p.getCreatedAt().toString());
            paymentDtos.add(pm);
            if ("SUCCEEDED".equalsIgnoreCase(p.getStatus())) {
                totalPaidCents += p.getAmountCents();
            }
        }

        // Dynamic Milestone Achievements calculated from account history
        List<UserAchievementDto> achievements = generateAchievements(user, daysSinceStart, activeSub, totalPaidCents);

        // Account Activity Timeline
        List<Map<String, Object>> timeline = new ArrayList<>();
        timeline.add(Map.of(
                "title", "Challenger Journey Initiated",
                "description", "Registered account and began the Winter ARC discipline protocol",
                "timestamp", startInstant.toString(),
                "type", "REGISTRATION"
        ));

        if (activeSub != null && activeSub.getTier() != SubscriptionTier.FREE) {
            timeline.add(Map.of(
                    "title", activeSub.getTier().getDisplayName() + " Unlocked",
                    "description", "Licensed via " + activeSub.getPaymentProvider(),
                    "timestamp", activeSub.getCurrentPeriodStart().toString(),
                    "type", "SUBSCRIPTION"
            ));
        }

        for (PaymentTransaction p : payments) {
            timeline.add(Map.of(
                    "title", "Payment Settled: " + p.getProvider(),
                    "description", String.format("Transaction %s (%s %d)", p.getEventId(), p.getCurrency().toUpperCase(), p.getAmountCents() / 100),
                    "timestamp", p.getCreatedAt().toString(),
                    "type", "PAYMENT"
            ));
        }

        timeline.sort((a, b) -> ((String) b.get("timestamp")).compareTo((String) a.get("timestamp")));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("userId", user.getId());
        response.put("email", user.getEmail());
        response.put("fullName", user.getFullName() != null ? user.getFullName() : "Challenger");
        response.put("role", user.getRole());
        response.put("status", user.getStatus());
        response.put("createdAt", startInstant.toString());
        response.put("startingDate", startingDateFormatted);
        response.put("daysSinceStart", daysSinceStart);
        response.put("subscription", subMap);
        response.put("payments", paymentDtos);
        response.put("achievements", achievements);
        response.put("timeline", timeline);
        response.put("totalPaidCents", totalPaidCents);

        return ResponseEntity.ok(response);
    }

    @PutMapping
    public ResponseEntity<?> updateProfile(@RequestBody UpdateProfileRequest req, Authentication auth) {
        User user = getAuthenticatedUser(auth);

        if (req.fullName() != null && !req.fullName().isBlank()) {
            user.setFullName(req.fullName().trim());
        }

        if (req.newPassword() != null && !req.newPassword().isBlank()) {
            if (req.currentPassword() == null || !passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
                return ResponseEntity.badRequest().body(Map.of("error", "Current password verification failed."));
            }
            if (req.newPassword().length() < 6) {
                return ResponseEntity.badRequest().body(Map.of("error", "New password must be at least 6 characters long."));
            }
            user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        }

        user.setUpdatedAt(Instant.now());
        User saved = userRepository.save(user);

        return ResponseEntity.ok(Map.of(
                "message", "Profile updated successfully",
                "fullName", saved.getFullName() != null ? saved.getFullName() : "",
                "email", saved.getEmail()
        ));
    }

    @GetMapping("/payments")
    public ResponseEntity<List<PaymentTransaction>> getMyPayments(Authentication auth) {
        User user = getAuthenticatedUser(auth);
        return ResponseEntity.ok(paymentRepository.findByUserIdOrderByCreatedAtDesc(user.getId()));
    }

    private List<UserAchievementDto> generateAchievements(User user, long daysSinceStart, Subscription activeSub, long totalPaidCents) {
        List<UserAchievementDto> list = new ArrayList<>();

        // 1. Day 1 Initiation
        list.add(new UserAchievementDto(
                "ach-001", "INITIATION", "First Step Taken",
                "Began the Winter ARC journey on " + DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.of("UTC")).format(user.getCreatedAt()),
                "star", 50, true, user.getCreatedAt().toString()
        ));

        // 2. Week 1 Warrior (7+ days)
        boolean week1Unlocked = daysSinceStart >= 7;
        list.add(new UserAchievementDto(
                "ach-002", "WEEK_ONE", "Iron Habit Initiate",
                "Maintained unbroken commitment across the first 7 days",
                "streak", 100, week1Unlocked,
                week1Unlocked ? user.getCreatedAt().plusSeconds(7L * 86400L).toString() : null
        ));

        // 3. Pro Pioneer
        boolean isPro = activeSub != null && activeSub.getTier() != SubscriptionTier.FREE && "ACTIVE".equalsIgnoreCase(activeSub.getStatus());
        list.add(new UserAchievementDto(
                "ach-003", "PRO_PIONEER", "Pro License Pioneer",
                "Unlocked full cryptographic Ed25519 offline Pro suite",
                "award", 200, isPro,
                isPro ? activeSub.getCurrentPeriodStart().toString() : null
        ));

        // 4. One Month Deep Work (30+ days)
        boolean month1Unlocked = daysSinceStart >= 30;
        list.add(new UserAchievementDto(
                "ach-004", "MONTH_ONE", "Deep Work Vanguard",
                "Completed 30 days of deliberate practice and daily consistency",
                "trophy", 300, month1Unlocked,
                month1Unlocked ? user.getCreatedAt().plusSeconds(30L * 86400L).toString() : null
        ));

        // 5. Winter ARC Grandmaster (90+ days or Lifetime)
        boolean isGrandmaster = daysSinceStart >= 90 || (activeSub != null && activeSub.getTier() == SubscriptionTier.LIFETIME);
        list.add(new UserAchievementDto(
                "ach-005", "ARC_GRANDMASTER", "Winter ARC Grandmaster",
                "Conquered the full 90-day Winter ARC protocol",
                "medal", 500, isGrandmaster,
                isGrandmaster ? Instant.now().toString() : null
        ));

        return list;
    }
}
