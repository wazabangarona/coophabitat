package sn.coophabitat.payment.provider;

import org.springframework.stereotype.Component;
import sn.coophabitat.config.AppProperties;
import sn.coophabitat.payment.ProviderCode;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * SIMULATION — AUCUN ARGENT RÉEL TRANSFÉRÉ.
 * <p>Fournisseur fictif destiné au développement, aux tests et aux démonstrations. Il n'est disponible que si
 * {@code coophabitat.payment.mock-enabled=true} ; le démarrage est refusé si cette option est active en
 * production ({@link sn.coophabitat.config.ProductionSafetyCheck}). Les paiements simulés sont marqués
 * comme tels en base, dans les écritures (compte 559900), les exports et les reçus.</p>
 * <p>La confirmation passe par le même circuit qu'un vrai fournisseur : notification signée (HMAC) et
 * traitement idempotent.</p>
 */
@Component
public class MockPaymentProvider implements PaymentProvider {

    private final AppProperties props;
    private final Clock clock;

    public MockPaymentProvider(AppProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    @Override
    public ProviderCode code() {
        return ProviderCode.MOCK;
    }

    @Override
    public boolean available() {
        return props.payment().mockEnabled() && !props.isProduction() && secret() != null;
    }

    @Override
    public String unavailableReason() {
        return "Simulation désactivée (réservée aux environnements de développement, de test et de démonstration)";
    }

    @Override
    public boolean simulated() {
        return true;
    }

    @Override
    public boolean supportsStatusCheck() {
        return false;
    }

    @Override
    public CheckoutSession createCheckout(CheckoutRequest request) {
        String sessionId = "sim-" + request.intentReference();
        String url = props.frontendBaseUrl() + "/espace/paiements/simulation/" + request.intentReference();
        return new CheckoutSession(sessionId, url, Instant.now(clock).plus(props.payment().intentTtl()));
    }

    @Override
    public ProviderPaymentStatus fetchStatus(String sessionId) throws ProviderException {
        throw new ProviderException("Le simulateur ne propose pas de vérification d'état", false, null);
    }

    @Override
    public void refund(String sessionId) {
        // Remboursement simulé : aucun mouvement réel.
    }

    @Override
    public WebhookEvent parseWebhook(Map<String, String> headers, String rawBody) throws InvalidWebhookException {
        if (!available()) {
            throw new InvalidWebhookException("Simulation désactivée");
        }
        WebhookSignature.verify(headers.get("wave-signature"), secret(), rawBody, clock);
        JsonNode node = sn.coophabitat.common.Json.mapper().readTree(rawBody);
        String type = node.path("type").asString();
        JsonNode data = node.path("data");
        State state = switch (type) {
            case "checkout.session.completed" -> State.REUSSI;
            case "checkout.session.payment_failed" -> State.ECHOUE;
            default -> State.INCONNU;
        };
        return new WebhookEvent(node.path("id").asString(), type, data.path("id").asString(),
                data.path("client_reference").asString(), state, data.path("transaction_id").asString(null),
                data.path("last_payment_error").path("message").asString(null));
    }

    public String secret() {
        String s = props.payment().mockWebhookSecret();
        return s == null || s.isBlank() ? null : s;
    }
}
