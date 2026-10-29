package com.winterarc.server.service;

import com.winterarc.server.model.PromoCode;
import com.winterarc.server.model.SubscriptionTier;
import com.winterarc.server.repository.PromoCodeRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.NoSuchElementException;

@Service
public class AdminPromoCodeService {

    private final PromoCodeRepository promoCodeRepository;
    private final AdminAuditService auditService;

    public AdminPromoCodeService(PromoCodeRepository promoCodeRepository,
                                 AdminAuditService auditService) {
        this.promoCodeRepository = promoCodeRepository;
        this.auditService = auditService;
    }

    public record CreatePromoCodeRequest(
            String code,
            int discountPercent,
            String tierGrant, // PRO_MONTHLY, PRO_YEARLY, or null
            Integer durationDays,
            Integer maxRedemptions,
            Integer validDays
    ) {}

    @Transactional(readOnly = true)
    public Page<PromoCode> getAllPromoCodes(Pageable pageable) {
        return promoCodeRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    @Transactional
    public PromoCode createPromoCode(String adminId, String adminEmail,
                                     CreatePromoCodeRequest req, String ipAddress) {
        if (req == null || req.code() == null || req.code().trim().length() < 3) {
            throw new IllegalArgumentException("Promo code must be at least 3 characters long.");
        }

        String normCode = req.code().trim().toUpperCase().replaceAll("[^A-Z0-9_-]", "");
        if (promoCodeRepository.existsByCode(normCode)) {
            throw new IllegalArgumentException("Promo code '" + normCode + "' already exists.");
        }

        if (req.discountPercent() < 0 || req.discountPercent() > 100) {
            throw new IllegalArgumentException("Discount percent must be between 0 and 100.");
        }

        SubscriptionTier tier = null;
        if (req.tierGrant() != null && !req.tierGrant().isBlank()) {
            tier = SubscriptionTier.valueOf(req.tierGrant().toUpperCase());
        }

        Instant expiresAt = null;
        if (req.validDays() != null && req.validDays() > 0) {
            expiresAt = Instant.now().plusSeconds(req.validDays() * 86400L);
        }

        int maxRedemptions = (req.maxRedemptions() != null && req.maxRedemptions() > 0) ? req.maxRedemptions() : 100;

        PromoCode promo = new PromoCode(
                normCode,
                req.discountPercent(),
                tier,
                req.durationDays(),
                maxRedemptions,
                expiresAt
        );

        PromoCode saved = promoCodeRepository.save(promo);

        auditService.record(
                adminId, adminEmail, "CREATE_PROMO_CODE",
                null, "PROMO:" + saved.getCode(),
                String.format("Created promo code %s: %d%% discount, max %d redemptions",
                        saved.getCode(), saved.getDiscountPercent(), saved.getMaxRedemptions()),
                "SUCCESS", "ID=" + saved.getId(), ipAddress
        );

        return saved;
    }

    @Transactional
    public PromoCode toggleStatus(String adminId, String adminEmail, String id, String ipAddress) {
        PromoCode promo = promoCodeRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Promo code not found: " + id));

        boolean newActive = !promo.isActive();
        promo.setActive(newActive);
        PromoCode saved = promoCodeRepository.save(promo);

        auditService.record(
                adminId, adminEmail, "TOGGLE_PROMO_CODE",
                null, "PROMO:" + saved.getCode(),
                String.format("Toggled promo code %s active status to %b", saved.getCode(), newActive),
                "SUCCESS", "ID=" + id, ipAddress
        );

        return saved;
    }
}
