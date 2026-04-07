package com.reconstream.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, UUID> {
    Optional<PaymentOrder> findByExternalOrderId(String externalOrderId);
    Optional<PaymentOrder> findByPspTxnId(String pspTxnId);

    @Query(value = "SELECT * FROM payment_orders WHERE state IN ('INITIATED', 'PENDING') AND created_at <= :createdBefore ORDER BY created_at ASC LIMIT 50 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<PaymentOrder> findStaleOrdersForUpdate(@Param("createdBefore") Instant createdBefore);
}
