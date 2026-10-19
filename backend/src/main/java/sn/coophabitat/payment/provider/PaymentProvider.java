package sn.coophabitat.payment.provider;

import sn.coophabitat.payment.ProviderCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Contrat commun des fournisseurs de paiement. Chaque implémentation isole les détails du fournisseur
 * (format des montants, authentification, signature des webhooks).
 */
public interface PaymentProvider {

    ProviderCode code();

    /** Le fournisseur est-il activé ET configuré avec des accès réels (ou, pour MOCK, explicitement autorisé) ? */
    boolean available();

    /** Raison lisible de l'indisponibilité (affichée telle quelle aux utilisateurs autorisés). */
    String unavailableReason();

    boolean simulated();

    /** Le fournisseur permet-il de vérifier l'état d'un paiement par une requête authentifiée ? */
    boolean supportsStatusCheck();

    CheckoutSession createCheckout(CheckoutRequest request) throws ProviderException;

    ProviderPaymentStatus fetchStatus(String sessionId) throws ProviderException;

    void refund(String sessionId) throws ProviderException;

    /**
     * Vérifie l'authenticité d'une notification (signature, horodatage) et en extrait l'événement.
     * @throws InvalidWebhookException si la notification n'est pas authentique
     */
    WebhookEvent parseWebhook(Map<String, String> headers, String rawBody) throws InvalidWebhookException;

    record CheckoutRequest(String intentReference, BigDecimal amount, String currency, String payerPhone) {
    }

    record CheckoutSession(String sessionId, String launchUrl, Instant expiresAt) {
    }

    enum State { EN_ATTENTE, REUSSI, ECHOUE, EXPIRE, INCONNU }

    record ProviderPaymentStatus(State state, String transactionId, String detail) {
    }

    record WebhookEvent(String eventId, String type, String sessionId, String clientReference, State state,
                        String transactionId, String detail) {
    }

    class ProviderException extends Exception {
        private final boolean retryable;

        public ProviderException(String message, boolean retryable, Throwable cause) {
            super(message, cause);
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    class InvalidWebhookException extends Exception {
        public InvalidWebhookException(String message) {
            super(message);
        }
    }
}
