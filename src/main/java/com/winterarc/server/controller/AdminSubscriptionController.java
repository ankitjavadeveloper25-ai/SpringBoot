package com.winterarc.server.controller;

import com.winterarc.server.model.Subscription;
import com.winterarc.server.model.SubscriptionGrant;
import com.winterarc.server.model.User;
import com.winterarc.server.repository.UserRepository;
import com.winterarc.server.service.AdminSubscriptionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminSubscriptionController {

    private final AdminSubscriptionService subscriptionService;
    private final UserRepository userRepository;

    public AdminSubscriptionController(AdminSubscriptionService subscriptionService, UserRepository userRepository) {
        this.subscriptionService = subscriptionService;
        this.userRepository = userRepository;
    }

    private User getAdmin(Authentication auth) {
        String adminId = (String) auth.getPrincipal();
        return userRepository.findById(adminId)
                .orElseThrow(() -> new IllegalStateException("Authenticated administrator not found"));
    }

    @PostMapping("/users/{userId}/complimentary-subscriptions")
    public ResponseEntity<?> grantComplimentarySubscription(
            @PathVariable String userId,
            @RequestBody AdminSubscriptionService.GrantRequest request,
            Authentication auth,
            HttpServletRequest servletRequest) {
        try {
            User admin = getAdmin(auth);
            SubscriptionGrant grant = subscriptionService.grantComplimentarySubscription(
                    admin.getId(), admin.getEmail(), userId, request, servletRequest.getRemoteAddr()
            );
            return ResponseEntity.ok(Map.of(
                    "message", "Complimentary Pro subscription granted successfully",
                    "grant", grant
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/users/{userId}/subscriptions/extend")
    public ResponseEntity<?> extendSubscription(
            @PathVariable String userId,
            @RequestBody AdminSubscriptionService.ExtendRequest request,
            Authentication auth,
            HttpServletRequest servletRequest) {
        try {
            User admin = getAdmin(auth);
            Subscription sub = subscriptionService.extendSubscription(
                    admin.getId(), admin.getEmail(), userId, request, servletRequest.getRemoteAddr()
            );
            return ResponseEntity.ok(Map.of(
                    "message", "Subscription extended successfully",
                    "newExpiryDate", sub.getCurrentPeriodEnd() != null ? sub.getCurrentPeriodEnd().toString() : "N/A"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/users/{userId}/subscriptions/revoke")
    public ResponseEntity<?> revokeSubscription(
            @PathVariable String userId,
            @RequestBody AdminSubscriptionService.RevokeRequest request,
            Authentication auth,
            HttpServletRequest servletRequest) {
        try {
            User admin = getAdmin(auth);
            Subscription sub = subscriptionService.revokeSubscription(
                    admin.getId(), admin.getEmail(), userId, request, servletRequest.getRemoteAddr()
            );
            return ResponseEntity.ok(Map.of(
                    "message", "Subscription revoked successfully",
                    "status", sub.getStatus()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/users/{userId}/subscriptions")
    public ResponseEntity<?> getUserSubscriptions(@PathVariable String userId) {
        try {
            return ResponseEntity.ok(subscriptionService.getUserSubscriptions(userId));
        } catch (Exception e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/users/{userId}/entitlements")
    public ResponseEntity<?> previewEntitlement(@PathVariable String userId) {
        try {
            return ResponseEntity.ok(subscriptionService.previewEntitlement(userId));
        } catch (Exception e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/subscription-grants")
    public ResponseEntity<?> getSubscriptionGrants(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size,
            @RequestParam(required = false) String status) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<SubscriptionGrant> grants = subscriptionService.getAllGrants(status, pageable);
        return ResponseEntity.ok(grants);
    }

    @GetMapping("/subscription-grants/export/csv")
    public ResponseEntity<byte[]> exportGrantsCsv() {
        Page<SubscriptionGrant> allGrants = subscriptionService.getAllGrants(null, PageRequest.of(0, 10000, Sort.by(Sort.Direction.DESC, "createdAt")));
        StringBuilder sb = new StringBuilder();
        sb.append("Grant ID,Recipient Email,Tier,Duration Days,Status,Reason,Admin Issuer,Created At\n");
        for (SubscriptionGrant g : allGrants.getContent()) {
            sb.append(escapeCsv(g.getId())).append(",")
              .append(escapeCsv(g.getUserEmail())).append(",")
              .append(escapeCsv(g.getTier() != null ? g.getTier().name() : "")).append(",")
              .append(escapeCsv(g.getDurationDays() != null ? g.getDurationDays().toString() : "Lifetime")).append(",")
              .append(escapeCsv(g.getStatus())).append(",")
              .append(escapeCsv(g.getReason())).append(",")
              .append(escapeCsv(g.getAdminEmail())).append(",")
              .append(escapeCsv(g.getCreatedAt().toString())).append("\n");
        }
        byte[] bytes = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"winterarc-complimentary-grants.csv\"")
                .contentType(org.springframework.http.MediaType.parseMediaType("text/csv"))
                .body(bytes);
    }

    private String escapeCsv(String input) {
        if (input == null) return "\"\"";
        String val = input;
        if (val.startsWith("=") || val.startsWith("+") || val.startsWith("-") || val.startsWith("@") || val.startsWith("\t") || val.startsWith("\r")) {
            val = "'" + val;
        }
        return "\"" + val.replace("\"", "\"\"") + "\"";
    }
}
