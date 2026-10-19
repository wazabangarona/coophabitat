package sn.coophabitat.payment.provider;

import org.springframework.stereotype.Component;
import sn.coophabitat.common.BusinessException;
import sn.coophabitat.payment.ProviderCode;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class PaymentProviderRegistry {

    private final Map<ProviderCode, PaymentProvider> providers = new EnumMap<>(ProviderCode.class);

    public PaymentProviderRegistry(List<PaymentProvider> list) {
        list.forEach(p -> providers.put(p.code(), p));
    }

    public PaymentProvider get(ProviderCode code) {
        PaymentProvider p = providers.get(code);
        if (p == null) {
            throw BusinessException.badRequest("FOURNISSEUR_INCONNU", "Fournisseur de paiement inconnu");
        }
        return p;
    }

    public List<PaymentProvider> all() {
        return List.copyOf(providers.values());
    }
}
