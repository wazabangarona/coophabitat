package sn.coophabitat.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentProviderEventRepository extends JpaRepository<PaymentProviderEvent, Long> {

    Optional<PaymentProviderEvent> findByProviderAndEventId(ProviderCode provider, String eventId);

    List<PaymentProviderEvent> findByIntentIdOrderByIdAsc(Long intentId);
}
