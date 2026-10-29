package com.winterarc.server.service;

import com.winterarc.server.model.*;
import com.winterarc.server.repository.PaymentTransactionRepository;
import com.winterarc.server.repository.SubscriptionRepository;
import com.winterarc.server.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
public class AdminUserService {

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PaymentTransactionRepository paymentRepository;
    private final AdminSubscriptionService subscriptionService;
    private final AdminAuditService auditService;
    private final PasswordEncoder passwordEncoder;
    private final String bootstrapEmail;

    public AdminUserService(UserRepository userRepository,
                            SubscriptionRepository subscriptionRepository,
                            PaymentTransactionRepository paymentRepository,
                            AdminSubscriptionService subscriptionService,
                            AdminAuditService auditService,
                            PasswordEncoder passwordEncoder,
                            @Value("${winterarc.admin.bootstrap.email:admin@winterarc.pro}") String bootstrapEmail) {
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.paymentRepository = paymentRepository;
        this.subscriptionService = subscriptionService;
        this.auditService = auditService;
        this.passwordEncoder = passwordEncoder;
        this.bootstrapEmail = bootstrapEmail != null ? bootstrapEmail.trim().toLowerCase() : "admin@winterarc.pro";
    }

    public record ChangePasswordRequest(
            String currentPassword,
            String newPassword,
            String confirmPassword
    ) {}

    public record UserDetailView(
            User user,
            Subscription activeSubscription,
            List<Subscription> subscriptionHistory,
            List<SubscriptionGrant> grantHistory,
            EntitlementToken entitlementPreview,
            List<PaymentTransaction> paymentHistory
    ) {}

    public record StatusUpdateRequest(
            String status, // "ACTIVE", "SUSPENDED"
            String reason
    ) {}

    public record RoleUpdateRequest(
            String role, // "ROLE_USER", "ROLE_ADMIN"
            String reason
    ) {}

    @Transactional(readOnly = true)
    public Page<User> searchUsers(String query, String role, String status, Pageable pageable) {
        return userRepository.searchUsers(query, role, status, pageable);
    }

    @Transactional(readOnly = true)
    public UserDetailView getUserDetails(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + userId));

        Subscription activeSub = subscriptionRepository.findTopByUserOrderByCreatedAtDesc(user).orElse(null);
        List<Subscription> history = subscriptionRepository.findByUserOrderByCreatedAtDesc(user);
        List<SubscriptionGrant> grants = subscriptionService.getUserGrants(userId);
        EntitlementToken preview = subscriptionService.previewEntitlement(userId);
        List<PaymentTransaction> payments = paymentRepository.findByUserIdOrderByCreatedAtDesc(userId);

        return new UserDetailView(user, activeSub, history, grants, preview, payments);
    }

    @Transactional
    public User updateUserStatus(String adminId, String adminEmail, String targetUserId,
                                 StatusUpdateRequest request, String ipAddress) {
        if (adminId.equals(targetUserId)) {
            throw new IllegalArgumentException("Administrators cannot suspend their own account.");
        }
        if (request.reason() == null || request.reason().trim().length() < 3) {
            throw new IllegalArgumentException("A valid reason is required for updating account status.");
        }
        if (!"ACTIVE".equalsIgnoreCase(request.status()) && !"SUSPENDED".equalsIgnoreCase(request.status())) {
            throw new IllegalArgumentException("Invalid status. Supported: ACTIVE, SUSPENDED");
        }

        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + targetUserId));

        if (user.getEmail().equalsIgnoreCase(bootstrapEmail)) {
            throw new IllegalArgumentException("Root administrator account cannot be suspended.");
        }

        String oldStatus = user.getStatus();
        String newStatus = request.status().toUpperCase();
        user.setStatus(newStatus);
        user.setUpdatedAt(Instant.now());
        User saved = userRepository.save(user);

        auditService.record(
                adminId, adminEmail,
                newStatus.equals("SUSPENDED") ? "SUSPEND_USER" : "ACTIVATE_USER",
                user.getId(), user.getEmail(), request.reason(), "SUCCESS",
                String.format("Status changed from %s to %s", oldStatus, newStatus), ipAddress
        );

        return saved;
    }

    @Transactional
    public User updateUserRole(String adminId, String adminEmail, String targetUserId,
                               RoleUpdateRequest request, String ipAddress) {
        if (adminId.equals(targetUserId) && !"ROLE_ADMIN".equalsIgnoreCase(request.role())) {
            throw new IllegalArgumentException("Administrators cannot revoke their own administrative privileges.");
        }
        if (request.reason() == null || request.reason().trim().length() < 3) {
            throw new IllegalArgumentException("A valid reason is required for modifying administrative privileges.");
        }

        String targetRole = request.role().toUpperCase();
        if (!targetRole.startsWith("ROLE_")) {
            targetRole = "ROLE_" + targetRole;
        }

        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + targetUserId));

        if ("ROLE_ADMIN".equalsIgnoreCase(targetRole) && !adminEmail.equalsIgnoreCase(bootstrapEmail)) {
            throw new AccessDeniedException("Sole-Administrator Policy: Only the root owner (" + bootstrapEmail + ") can elevate accounts to administrative status.");
        }
        if (user.getEmail().equalsIgnoreCase(bootstrapEmail) && !"ROLE_ADMIN".equalsIgnoreCase(targetRole)) {
            throw new IllegalArgumentException("Root administrator account cannot be demoted.");
        }

        String oldRole = user.getRole();
        user.setRole(targetRole);
        user.setUpdatedAt(Instant.now());
        User saved = userRepository.save(user);

        auditService.record(
                adminId, adminEmail, "UPDATE_USER_ROLE",
                user.getId(), user.getEmail(), request.reason(), "SUCCESS",
                String.format("Role changed from %s to %s", oldRole, targetRole), ipAddress
        );

        return saved;
    }

    @Transactional
    public void changeAdminPassword(String adminId, String adminEmail, ChangePasswordRequest req, String ipAddress) {
        if (req == null || req.currentPassword() == null || req.newPassword() == null) {
            throw new IllegalArgumentException("Current password and new password are required.");
        }
        if (!req.newPassword().equals(req.confirmPassword())) {
            throw new IllegalArgumentException("New password and confirmation do not match.");
        }
        if (req.newPassword().length() < 8) {
            throw new IllegalArgumentException("New password must be at least 8 characters long.");
        }
        if (!req.newPassword().matches(".*[A-Z].*") || !req.newPassword().matches(".*[a-z].*") ||
            !req.newPassword().matches(".*[0-9].*") || !req.newPassword().matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?].*")) {
            throw new IllegalArgumentException("New password must include uppercase, lowercase, numbers, and special symbols.");
        }
        if (req.currentPassword().equals(req.newPassword())) {
            throw new IllegalArgumentException("New password cannot be identical to the current password.");
        }

        User admin = userRepository.findById(adminId)
                .orElseThrow(() -> new NoSuchElementException("Administrator not found"));

        if (!passwordEncoder.matches(req.currentPassword(), admin.getPasswordHash())) {
            throw new IllegalArgumentException("Current password verification failed.");
        }

        admin.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        admin.setUpdatedAt(Instant.now());
        userRepository.save(admin);

        auditService.record(
                adminId, adminEmail, "ADMIN_PASSWORD_CHANGE",
                admin.getId(), admin.getEmail(), "Administrator password updated via security console",
                "SUCCESS", "BCrypt hash regenerated and stored", ipAddress
        );
    }
}
