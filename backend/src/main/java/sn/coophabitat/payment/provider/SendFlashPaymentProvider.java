package sn.coophabitat.payment.provider;

import org.springframework.stereotype.Component;
import sn.coophabitat.payment.ProviderCode;

import java.util.Map;

/**
 * Adaptateur SendFlash : DÉSACTIVÉ.
 * <p>Aucune documentation technique officielle ni accès d'intégration n'a pu être confirmé lors du
 * développement. Les endpoints, le protocole d'authentification et le format des webhooks ne sont donc
 * pas implémentés : les inventer constituerait une fausse intégration. Voir docs/paiements.md pour la liste
 * des éléments à obtenir auprès du fournisseur.</p>
 */
@Component
public class SendFlashPaymentProvider implements PaymentProvider {

    private static final String REASON = "SendFlash indisponible : documentation officielle et accès d'intégration non confirmés";

    @Override
    public ProviderCode code() {
        return ProviderCode.SENDFLASH;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String unavailableReason() {
        return REASON;
    }

    @Override
    public boolean simulated() {
        return false;
    }

    @Override
    public boolean supportsStatusCheck() {
        return false;
    }

    @Override
    public CheckoutSession createCheckout(CheckoutRequest request) throws ProviderException {
        throw new ProviderException(REASON, false, null);
    }

    @Override
    public ProviderPaymentStatus fetchStatus(String sessionId) throws ProviderException {
        throw new ProviderException(REASON, false, null);
    }

    @Override
    public void refund(String sessionId) throws ProviderException {
        throw new ProviderException(REASON, false, null);
    }

    @Override
    public WebhookEvent parseWebhook(Map<String, String> headers, String rawBody) throws InvalidWebhookException {
        throw new InvalidWebhookException(REASON);
    }
}
