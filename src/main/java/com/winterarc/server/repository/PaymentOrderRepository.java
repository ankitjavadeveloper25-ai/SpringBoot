package com.winterarc.server.repository;

import com.winterarc.server.model.PaymentOrder;
import com.winterarc.server.model.SubscriptionTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, String> {
    Optional<PaymentOrder> findByRazorpayOrderId(String razorpayOrderId);

    Optional<PaymentOrder> findByRazorpayPaymentId(String razorpayPaymentId);

    Optional<PaymentOrder> findByIdempotencyKey(String idempotencyKey);

    Optional<PaymentOrder> findByReceipt(String receipt);

    Optional<PaymentOrder> findFirstByUserIdAndPlanAndCurrencyAndStatusOrderByCreatedAtDesc(
            String userId, SubscriptionTier plan, String currency, String status);

    List<PaymentOrder> findByUserIdOrderByCreatedAtDesc(String userId);
}
