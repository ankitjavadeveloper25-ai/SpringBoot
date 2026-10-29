package com.winterarc.server.service;

import com.winterarc.server.model.AdminAuditLog;
import com.winterarc.server.model.SubscriptionGrant;
import com.winterarc.server.model.SubscriptionTier;
import com.winterarc.server.repository.PaymentTransactionRepository;
import com.winterarc.server.repository.SubscriptionGrantRepository;
import com.winterarc.server.repository.SubscriptionRepository;
import com.winterarc.server.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class AdminDashboardService {

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionGrantRepository grantRepository;
    private final PaymentTransactionRepository paymentRepository;
    private final AdminAuditService auditService;

    public AdminDashboardService(UserRepository userRepository,
                                 SubscriptionRepository subscriptionRepository,
                                 SubscriptionGrantRepository grantRepository,
                                 PaymentTransactionRepository paymentRepository,
                                 AdminAuditService auditService) {
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.grantRepository = grantRepository;
        this.paymentRepository = paymentRepository;
        this.auditService = auditService;
    }

    public record DashboardStats(
            long totalUsers,
            long activeUsers,
            long suspendedUsers,
            long newUsersToday,
            long newUsersPast7Days,
            long newUsersPast30Days,
            long totalSubscriptions,
            long activeSubscriptions,
            long activeProSubscribers,
            long freeSubscribers,
            long complimentaryGrantsCount,
            long totalRevenueCents,
            Map<String, Long> subscriptionDistribution,
            List<AdminAuditLog> recentAuditLogs,
            List<SubscriptionGrant> recentGrants
    ) {}

    @Transactional(readOnly = true)
    public DashboardStats getDashboardStats() {
        Instant now = Instant.now();
        Instant startOfToday = now.truncatedTo(ChronoUnit.DAYS);
        Instant past7Days = now.minus(7, ChronoUnit.DAYS);
        Instant past30Days = now.minus(30, ChronoUnit.DAYS);

        long totalUsers = userRepository.count();
        long activeUsers = userRepository.countByStatus("ACTIVE");
        long suspendedUsers = userRepository.countByStatus("SUSPENDED");
        long newUsersToday = userRepository.countByCreatedAtAfter(startOfToday);
        long newUsersPast7Days = userRepository.countByCreatedAtAfter(past7Days);
        long newUsersPast30Days = userRepository.countByCreatedAtAfter(past30Days);

        long totalSubscriptions = subscriptionRepository.count();
        long activeSubscriptions = subscriptionRepository.countByStatus("ACTIVE");
        long freeSubscribers = subscriptionRepository.countByTier(SubscriptionTier.FREE);
        long activeProMonthly = subscriptionRepository.countByTierAndStatus(SubscriptionTier.PRO_MONTHLY, "ACTIVE");
        long activeProYearly = subscriptionRepository.countByTierAndStatus(SubscriptionTier.PRO_YEARLY, "ACTIVE");
        long activeLifetime = subscriptionRepository.countByTierAndStatus(SubscriptionTier.LIFETIME, "ACTIVE");
        long activeProSubscribers = activeProMonthly + activeProYearly + activeLifetime;

        long complimentaryGrantsCount = grantRepository.countByStatus("ACTIVE");
        long totalRevenueCents = paymentRepository.sumSucceededAmount();

        Map<String, Long> tierDistribution = new LinkedHashMap<>();
        tierDistribution.put("FREE", freeSubscribers);
        tierDistribution.put("PRO_MONTHLY", subscriptionRepository.countByTier(SubscriptionTier.PRO_MONTHLY));
        tierDistribution.put("PRO_YEARLY", subscriptionRepository.countByTier(SubscriptionTier.PRO_YEARLY));
        tierDistribution.put("LIFETIME", subscriptionRepository.countByTier(SubscriptionTier.LIFETIME));

        List<AdminAuditLog> recentAuditLogs = auditService.getRecentAuditLogs();
        List<SubscriptionGrant> recentGrants = grantRepository.findAll(
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"))
        ).getContent();

        return new DashboardStats(
                totalUsers,
                activeUsers,
                suspendedUsers,
                newUsersToday,
                newUsersPast7Days,
                newUsersPast30Days,
                totalSubscriptions,
                activeSubscriptions,
                activeProSubscribers,
                freeSubscribers,
                complimentaryGrantsCount,
                totalRevenueCents,
                tierDistribution,
                recentAuditLogs,
                recentGrants
        );
    }
}
