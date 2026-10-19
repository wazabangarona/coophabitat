package sn.coophabitat.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface PaymentAllocationRepository extends JpaRepository<PaymentAllocation, Long> {

    List<PaymentAllocation> findByTransactionIdOrderByIdAsc(Long transactionId);

    List<PaymentAllocation> findByObligationIdOrderByIdAsc(Long obligationId);

    @Query("select coalesce(sum(a.amount), 0) from PaymentAllocation a where a.transactionId = :transactionId")
    BigDecimal sumForTransaction(Long transactionId);

    /** Montant imputé sur une échéance avant un instant donné (base du calcul reproductible des pénalités). */
    @Query("select coalesce(sum(a.amount), 0) from PaymentAllocation a where a.obligationId = :obligationId and a.createdAt < :before")
    BigDecimal sumForObligationBefore(Long obligationId, Instant before);

    boolean existsByReversalOfId(Long reversalOfId);
}
