package com.winterarc.server.repository;

import com.winterarc.server.model.SubscriptionGrant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionGrantRepository extends JpaRepository<SubscriptionGrant, String> {
    List<SubscriptionGrant> findByUserIdOrderByCreatedAtDesc(String userId);
    Page<SubscriptionGrant> findByStatus(String status, Pageable pageable);
    Page<SubscriptionGrant> findByUserEmailContainingIgnoreCase(String email, Pageable pageable);
    long countByStatus(String status);
    Optional<SubscriptionGrant> findFirstByUserIdAndStatusOrderByCreatedAtDesc(String userId, String status);
}
