package com.winterarc.server.service;

import com.winterarc.server.exception.InvalidPaymentRequestException;
import com.winterarc.server.model.SubscriptionTier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Authoritative Server-Side Plan Catalog Service.
 * Centralizes pricing, currency, duration, feature entitlements, and commercial terms.
 * Never allows client-supplied prices to dictate financial transactions.
 */
@Service
public class PlanCatalogService {

    @Value("${winterarc.plans.plan-a.price-inr:50}")
    private long planAPriceInr;

    @Value("${winterarc.plans.plan-b.price-inr:100}")
    private long planBPriceInr;

    @Value("${winterarc.plans.plan-a.duration-days:30}")
    private int planADurationDays;

    @Value("${winterarc.plans.plan-b.duration-days:90}")
    private int planBDurationDays;

    public record ResolvedPlan(
            String planId,
            String displayName,
            SubscriptionTier tier,
            long amountPaise,
            long priceInr,
            String currency,
            int durationDays,
            String billingPeriodLabel,
            String description,
            boolean isPopular,
            List<String> features,
            String commercialNotice
    ) {}

    public List<Map<String, Object>> getPublicCatalog() {
        List<Map<String, Object>> list = new ArrayList<>();

        // 1. Free Tier
        Map<String, Object> free = new LinkedHashMap<>();
        free.put("id", "FREE");
        free.put("tier", SubscriptionTier.FREE.name());
        free.put("name", "Free Edition");
        free.put("priceInr", 0);
        free.put("amountPaise", 0);
        free.put("currency", "INR");
        free.put("billingPeriod", "Forever Free");
        free.put("durationDays", 0);
        free.put("popular", false);
        free.put("description", "Core habit tracking and basic consistency calendar for self-driven individuals.");
        free.put("features", List.of(
                "Up to 5 Active Habits",
                "Daily Progress Checklist",
                "Basic Consistency Heatmap",
                "100% Offline Local SQLite Storage",
                "Zero Cloud Telemetry on Habits"
        ));
        free.put("commercialNotice", "Free forever with zero credit card required.");
        list.add(free);

        // 2. Pro Plan A — ₹50
        long amountAPaise = planAPriceInr * 100L;
        Map<String, Object> planA = new LinkedHashMap<>();
        planA.put("id", "PRO_PLAN_A");
        planA.put("tier", SubscriptionTier.PRO_MONTHLY.name());
        planA.put("name", "Pro Plan A");
        planA.put("priceInr", planAPriceInr);
        planA.put("amountPaise", amountAPaise);
        planA.put("currency", "INR");
        planA.put("billingPeriod", planADurationDays + "-Day Sprint");
        planA.put("durationDays", planADurationDays);
        planA.put("popular", false);
        planA.put("description", "Entry-level Pro discipline package to kickstart your Winter ARC transformation.");
        planA.put("features", List.of(
                "Unlimited Habits & Routines",
                "Full 90-Day Challenge Matrix",
                "Pomodoro Focus Timer & Audio Alerts",
                "Consistency Heatmap & Analytics",
                "14-Day Offline Grace Period",
                "Cryptographic Ed25519 Local Verification",
                "Local SQLite Backup & Restore"
        ));
        planA.put("commercialNotice", "Configurable duration (default " + planADurationDays + " days) pending final business decision.");
        list.add(planA);

        // 3. Pro Plan B — ₹100
        long amountBPaise = planBPriceInr * 100L;
        Map<String, Object> planB = new LinkedHashMap<>();
        planB.put("id", "PRO_PLAN_B");
        planB.put("tier", SubscriptionTier.PRO_YEARLY.name());
        planB.put("name", "Pro Plan B");
        planB.put("priceInr", planBPriceInr);
        planB.put("amountPaise", amountBPaise);
        planB.put("currency", "INR");
        planB.put("billingPeriod", "Full " + planBDurationDays + "-Day Cycle");
        planB.put("durationDays", planBDurationDays);
        planB.put("popular", true);
        planB.put("description", "Complete power package including the 90-day challenge and technical placement roadmap.");
        planB.put("features", List.of(
                "Everything in Pro Plan A",
                "Technical Interview Placement Roadmap",
                "DSA Problem Tracker & LeetCode Log",
                "Company Target & Interview Prep Planner",
                "Deep Work Analytics & Timeline Insights",
                "Full 90-Day Challenge Cycle Coverage",
                "Priority Roadmap Voting & Direct Support"
        ));
        planB.put("commercialNotice", "Configurable duration (default " + planBDurationDays + " days) pending final business decision.");
        list.add(planB);

        return list;
    }

    /**
     * Resolves an incoming plan identifier string to its authoritative pricing and entitlement specification.
     */
    public ResolvedPlan resolvePlan(String planIdentifier) {
        if (planIdentifier == null || planIdentifier.isBlank()) {
            throw new InvalidPaymentRequestException("Plan identifier is required. Supported plans: PRO_PLAN_A, PRO_PLAN_B, PRO_MONTHLY, PRO_YEARLY, LIFETIME");
        }

        String normalized = planIdentifier.trim().toUpperCase().replace("-", "_");

        if ("FREE".equals(normalized)) {
            throw new InvalidPaymentRequestException("FREE tier cannot be purchased via checkout");
        }

        if ("PRO_PLAN_A".equals(normalized) || "PRO_A".equals(normalized) || "PLAN_A".equals(normalized)) {
            long paise = planAPriceInr * 100L;
            return new ResolvedPlan(
                    "PRO_PLAN_A",
                    "Pro Plan A",
                    SubscriptionTier.PRO_MONTHLY,
                    paise,
                    planAPriceInr,
                    "INR",
                    planADurationDays,
                    planADurationDays + " Days",
                    "Pro Plan A Habit Package",
                    false,
                    List.of("Unlimited Habits", "90-Day Challenge Matrix", "Pomodoro Timer", "14-Day Offline Grace"),
                    "Configurable duration (default " + planADurationDays + " days)"
            );
        }

        if ("PRO_PLAN_B".equals(normalized) || "PRO_B".equals(normalized) || "PLAN_B".equals(normalized)) {
            long paise = planBPriceInr * 100L;
            return new ResolvedPlan(
                    "PRO_PLAN_B",
                    "Pro Plan B",
                    SubscriptionTier.PRO_YEARLY,
                    paise,
                    planBPriceInr,
                    "INR",
                    planBDurationDays,
                    planBDurationDays + " Days",
                    "Pro Plan B Placement & Discipline Package",
                    true,
                    List.of("Everything in Plan A", "Placement Prep Roadmap", "DSA Log", "Advanced Analytics"),
                    "Configurable duration (default " + planBDurationDays + " days)"
            );
        }

        // Backward-compatible fallback to core enum tiers
        try {
            SubscriptionTier tier = SubscriptionTier.valueOf(normalized);
            int durationDays = (tier == SubscriptionTier.PRO_MONTHLY) ? 30 : (tier == SubscriptionTier.PRO_YEARLY) ? 365 : 36500;
            return new ResolvedPlan(
                    tier.name(),
                    tier.getDisplayName(),
                    tier,
                    tier.getInrPaise(),
                    tier.getInrPaise() / 100L,
                    "INR",
                    durationDays,
                    tier == SubscriptionTier.PRO_MONTHLY ? "Monthly" : tier == SubscriptionTier.PRO_YEARLY ? "Yearly" : "Lifetime",
                    tier.getDisplayName() + " License",
                    tier == SubscriptionTier.PRO_YEARLY,
                    List.of("Unlimited Habits", "Challenge Matrix", "Analytics", "14-Day Offline Grace"),
                    "Standard Catalog Tier"
            );
        } catch (IllegalArgumentException e) {
            throw new InvalidPaymentRequestException("Invalid subscription plan: '" + planIdentifier + "'. Supported plans: PRO_PLAN_A, PRO_PLAN_B, PRO_MONTHLY, PRO_YEARLY, LIFETIME");
        }
    }
}
