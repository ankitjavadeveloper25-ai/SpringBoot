package com.winterarc.server.repository;

import com.winterarc.server.model.PaymentTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, String> {
    Optional<PaymentTransaction> findByEventId(String eventId);
    boolean existsByEventId(String eventId);

    Optional<PaymentTransaction> findByOrderId(String orderId);
    Optional<PaymentTransaction> findByPaymentId(String paymentId);

    List<PaymentTransaction> findByUserIdOrderByCreatedAtDesc(String userId);
    Page<PaymentTransaction> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByStatus(String status);

    @Query("SELECT COALESCE(SUM(p.amountCents), 0) FROM PaymentTransaction p WHERE p.status = 'SUCCEEDED'")
    long sumSucceededAmount();
}
