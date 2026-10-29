package com.winterarc.server.service;

import com.winterarc.server.model.*;
import com.winterarc.server.repository.SubscriptionGrantRepository;
import com.winterarc.server.repository.SubscriptionRepository;
import com.winterarc.server.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class AdminSubscriptionService {

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionGrantRepository grantRepository;
    private final AdminAuditService auditService;
    private final EntitlementSigningService signingService;

    public AdminSubscriptionService(
            UserRepository userRepository,
            SubscriptionRepository subscriptionRepository,
            SubscriptionGrantRepository grantRepository,
            AdminAuditService auditService,
            EntitlementSigningService signingService) {
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.grantRepository = grantRepository;
        this.auditService = auditService;
        this.signingService = signingService;
    }

    public record GrantRequest(
            SubscriptionTier tier,
            Integer durationDays,
            Instant customExpiryDate,
            String grantType,
            String reason
    ) {}

    public record ExtendRequest(
            int additionalDays,
            String reason
    ) {}

    public record RevokeRequest(
            String reason
    ) {}

    /**
     * Grants a complimentary Pro subscription to a user without processing payment.
     */
    @Transactional
    public SubscriptionGrant grantComplimentarySubscription(
            String adminId, String adminEmail, String targetUserId, GrantRequest request, String ipAddress) {
        if (request.reason() == null || request.reason().trim().length() < 3) {
            throw new IllegalArgumentException("A valid reason (minimum 3 characters) is required for granting complimentary access.");
        }

        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + targetUserId));

        SubscriptionTier tier = request.tier() != null ? request.tier() : SubscriptionTier.PRO_MONTHLY;
        if (tier == SubscriptionTier.FREE) {
            throw new IllegalArgumentException("Complimentary grant cannot be for the FREE tier.");
        }

        Instant now = Instant.now();
        Instant startDate = now;
        Instant expiryDate;

        if (tier == SubscriptionTier.LIFETIME || (request.durationDays() != null && request.durationDays() >= 36500)) {
            expiryDate = now.plusSeconds(100L * 365L * 86400L); // 100 years
        } else if (request.customExpiryDate() != null) {
            if (request.customExpiryDate().isBefore(now)) {
                throw new IllegalArgumentException("Custom expiry date must be in the future.");
            }
            expiryDate = request.customExpiryDate();
        } else {
            int days = request.durationDays() != null && request.durationDays() > 0 ? request.durationDays() : 30;
            expiryDate = now.plusSeconds((long) days * 86400L);
        }

        // Check current subscription
        Optional<Subscription> currentSubOpt = subscriptionRepository.findTopByUserOrderByCreatedAtDesc(user);
        Subscription subscription;

        if (currentSubOpt.isPresent()) {
            Subscription existing = currentSubOpt.get();
            // If already active with paid provider, extend from their current period end to avoid erasing paid time
            if (existing.isActive() && !"COMPLIMENTARY".equalsIgnoreCase(existing.getPaymentProvider())
                    && !"MANUAL".equalsIgnoreCase(existing.getPaymentProvider())
                    && existing.getCurrentPeriodEnd() != null
                    && existing.getCurrentPeriodEnd().isAfter(now)) {
                startDate = existing.getCurrentPeriodEnd();
                if (tier != SubscriptionTier.LIFETIME) {
                    int days = request.durationDays() != null && request.durationDays() > 0 ? request.durationDays() : 30;
                    expiryDate = startDate.plusSeconds((long) days * 86400L);
                }
            }
            subscription = existing;
            subscription.setTier(tier);
            subscription.setStatus("ACTIVE");
            subscription.setPaymentProvider("COMPLIMENTARY");
            subscription.setCurrentPeriodStart(now);
            subscription.setCurrentPeriodEnd(expiryDate);
            subscription.setUpdatedAt(now);
        } else {
            subscription = new Subscription(
                    user, tier, "ACTIVE", "COMPLIMENTARY", startDate, expiryDate
            );
        }
        subscription = subscriptionRepository.save(subscription);

        // Record SubscriptionGrant
        SubscriptionGrant grant = new SubscriptionGrant(
                user.getId(),
                user.getEmail(),
                subscription.getId(),
                adminId,
                adminEmail,
                request.grantType() != null ? request.grantType() : "COMPLIMENTARY",
                tier,
                request.durationDays(),
                startDate,
                expiryDate,
                request.reason().trim()
        );
        grant = grantRepository.save(grant);

        // Durable Audit Log
        String details = String.format("Tier: %s, Expiry: %s, Duration: %s days, GrantId: %s",
                tier.name(), expiryDate.toString(), request.durationDays() != null ? request.durationDays() : "LIFETIME", grant.getId());
        auditService.record(
                adminId, adminEmail, "GRANT_COMPLIMENTARY",
                user.getId(), user.getEmail(), request.reason(), "SUCCESS", details, ipAddress
        );

        return grant;
    }

    /**
     * Extends an active subscription by additional days.
     */
    @Transactional
    public Subscription extendSubscription(
            String adminId, String adminEmail, String targetUserId, ExtendRequest request, String ipAddress) {
        if (request.reason() == null || request.reason().trim().length() < 3) {
            throw new IllegalArgumentException("A valid reason is required for extending a subscription.");
        }
        if (request.additionalDays() <= 0) {
            throw new IllegalArgumentException("Additional days must be greater than zero.");
        }

        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + targetUserId));

        Subscription sub = subscriptionRepository.findTopByUserOrderByCreatedAtDesc(user)
                .orElseThrow(() -> new IllegalStateException("No subscription found for user: " + targetUserId));

        Instant currentEnd = sub.getCurrentPeriodEnd() != null && sub.getCurrentPeriodEnd().isAfter(Instant.now())
                ? sub.getCurrentPeriodEnd() : Instant.now();

        Instant newEnd = currentEnd.plusSeconds((long) request.additionalDays() * 86400L);
        sub.setCurrentPeriodEnd(newEnd);
        sub.setStatus("ACTIVE");
        sub.setUpdatedAt(Instant.now());
        sub = subscriptionRepository.save(sub);

        // Update grant if one exists
        grantRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(user.getId(), "ACTIVE")
                .ifPresent(g -> {
                    g.setExpiryDate(newEnd);
                    if (g.getDurationDays() != null) {
                        g.setDurationDays(g.getDurationDays() + request.additionalDays());
                    }
                    g.setUpdatedAt(Instant.now());
                    grantRepository.save(g);
                });

        String details = String.format("Extended by %d days. New expiry: %s", request.additionalDays(), newEnd);
        auditService.record(
                adminId, adminEmail, "EXTEND_SUBSCRIPTION",
                user.getId(), user.getEmail(), request.reason(), "SUCCESS", details, ipAddress
        );

        return sub;
    }

    /**
     * Revokes an active subscription.
     */
    @Transactional
    public Subscription revokeSubscription(
            String adminId, String adminEmail, String targetUserId, RevokeRequest request, String ipAddress) {
        if (request.reason() == null || request.reason().trim().length() < 3) {
            throw new IllegalArgumentException("A valid reason is required for revoking a subscription.");
        }

        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + targetUserId));

        Subscription sub = subscriptionRepository.findTopByUserOrderByCreatedAtDesc(user)
                .orElseThrow(() -> new IllegalStateException("No subscription found for user: " + targetUserId));

        sub.setStatus("CANCELLED");
        sub.setCancelledAt(Instant.now());
        sub.setUpdatedAt(Instant.now());
        sub = subscriptionRepository.save(sub);

        // Update grant status if active
        grantRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(user.getId(), "ACTIVE")
                .ifPresent(g -> {
                    g.setStatus("REVOKED");
                    g.setRevokedAt(Instant.now());
                    g.setRevokedByAdminId(adminId);
                    g.setRevocationReason(request.reason());
                    g.setUpdatedAt(Instant.now());
                    grantRepository.save(g);
                });

        auditService.record(
                adminId, adminEmail, "REVOKE_SUBSCRIPTION",
                user.getId(), user.getEmail(), request.reason(), "SUCCESS", "Subscription revoked", ipAddress
        );

        return sub;
    }

    @Transactional(readOnly = true)
    public List<Subscription> getUserSubscriptions(String targetUserId) {
        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + targetUserId));
        return subscriptionRepository.findByUserOrderByCreatedAtDesc(user);
    }

    @Transactional(readOnly = true)
    public List<SubscriptionGrant> getUserGrants(String targetUserId) {
        return grantRepository.findByUserIdOrderByCreatedAtDesc(targetUserId);
    }

    @Transactional(readOnly = true)
    public Page<SubscriptionGrant> getAllGrants(String status, Pageable pageable) {
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            return grantRepository.findByStatus(status.toUpperCase(), pageable);
        }
        return grantRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public EntitlementToken previewEntitlement(String targetUserId) {
        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + targetUserId));
        Subscription sub = subscriptionRepository.findTopByUserOrderByCreatedAtDesc(user).orElse(null);
        return signingService.generateEntitlement(user, sub);
    }
}
