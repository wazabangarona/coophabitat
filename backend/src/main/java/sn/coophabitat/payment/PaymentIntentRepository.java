package sn.coophabitat.payment;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, Long> {

    Optional<PaymentIntent> findByMemberIdAndIdempotencyKey(Long memberId, String idempotencyKey);

    boolean existsByProviderAndChannelAndExternalReferenceAndStatusNot(ProviderCode provider, String channel,
                                                                        String externalReference, PaymentStatus status);

    Optional<PaymentIntent> findByReference(String reference);

    Optional<PaymentIntent> findByProviderAndProviderSessionId(ProviderCode provider, String providerSessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from PaymentIntent i where i.id = :id")
    Optional<PaymentIntent> lockById(Long id);

    Page<PaymentIntent> findByMemberIdOrderByCreatedAtDesc(Long memberId, Pageable pageable);

    @Query("""
            select i from PaymentIntent i
            where (:status is null or i.status = :status) and (:provider is null or i.provider = :provider)
              and (:memberId is null or i.memberId = :memberId) and (:simulated is null or i.simulated = :simulated)
            order by i.createdAt desc
            """)
    Page<PaymentIntent> search(PaymentStatus status, ProviderCode provider, Long memberId, Boolean simulated,
                               Pageable pageable);

    @Query("select i from PaymentIntent i where i.status in :statuses and i.provider in :providers "
            + "and (i.lastCheckedAt is null or i.lastCheckedAt < :before) order by i.id")
    List<PaymentIntent> findToReconcile(Collection<PaymentStatus> statuses, Collection<ProviderCode> providers,
                                        Instant before, Pageable pageable);

    @Query("select coalesce(sum(i.amount), 0) from PaymentIntent i where i.status in :statuses and i.simulated = :simulated")
    BigDecimal sumByStatuses(Collection<PaymentStatus> statuses, boolean simulated);

    long countByStatusInAndSimulated(Collection<PaymentStatus> statuses, boolean simulated);
}
