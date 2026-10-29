package com.winterarc.server.repository;

import com.winterarc.server.model.Subscription;
import com.winterarc.server.model.SubscriptionTier;
import com.winterarc.server.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, String> {
    List<Subscription> findByUser(User user);
    List<Subscription> findByUserOrderByCreatedAtDesc(User user);
    Optional<Subscription> findTopByUserOrderByCreatedAtDesc(User user);
    Optional<Subscription> findByExternalSubscriptionId(String externalSubscriptionId);

    long countByStatus(String status);
    long countByTier(SubscriptionTier tier);
    long countByTierAndStatus(SubscriptionTier tier, String status);
    long countByPaymentProvider(String paymentProvider);

    Page<Subscription> findByStatus(String status, Pageable pageable);
}
