package sn.coophabitat.payment;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    Optional<PaymentTransaction> findByIntentId(Long intentId);

    Optional<PaymentTransaction> findByReference(String reference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PaymentTransaction t where t.id = :id")
    Optional<PaymentTransaction> lockById(Long id);

    Page<PaymentTransaction> findByMemberIdOrderByConfirmedAtDesc(Long memberId, Pageable pageable);

    List<PaymentTransaction> findByMemberIdOrderByConfirmedAtAsc(Long memberId);

    /** Transactions confirmées présentant un reliquat non imputé (avance), du plus ancien au plus récent. */
    @Query(value = """
            SELECT t.id, t.amount - COALESCE((SELECT SUM(a.amount) FROM payment_allocation a WHERE a.transaction_id = t.id), 0)
            FROM payment_transaction t
            WHERE t.member_id = :memberId AND t.status = 'CONFIRME'
              AND t.amount > COALESCE((SELECT SUM(a.amount) FROM payment_allocation a WHERE a.transaction_id = t.id), 0)
            ORDER BY t.confirmed_at, t.id
            """, nativeQuery = true)
    List<Object[]> findUnallocated(Long memberId);

    @Query(value = """
            SELECT COALESCE(SUM(t.amount - COALESCE((SELECT SUM(a.amount) FROM payment_allocation a WHERE a.transaction_id = t.id), 0)), 0)
            FROM payment_transaction t WHERE t.member_id = :memberId AND t.status = 'CONFIRME'
            """, nativeQuery = true)
    BigDecimal creditBalance(Long memberId);

    @Query("select coalesce(sum(t.amount), 0) from PaymentTransaction t where t.status = :status and t.simulated = :simulated "
            + "and t.confirmedAt >= :from and t.confirmedAt < :to")
    BigDecimal sumConfirmedBetween(PaymentTransaction.Status status, boolean simulated, Instant from, Instant to);

    @Query("select coalesce(sum(t.amount), 0) from PaymentTransaction t where t.status = sn.coophabitat.payment.PaymentTransaction.Status.CONFIRME "
            + "and t.simulated = :simulated")
    BigDecimal sumConfirmed(boolean simulated);

    long countByStatusAndSimulated(PaymentTransaction.Status status, boolean simulated);
}
