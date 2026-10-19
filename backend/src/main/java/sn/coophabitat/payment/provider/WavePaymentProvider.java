package sn.coophabitat.payment.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import sn.coophabitat.common.Json;
import sn.coophabitat.config.AppProperties;
import sn.coophabitat.payment.ProviderCode;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adaptateur Wave Checkout, conforme à la documentation officielle consultée (https://docs.wave.com/checkout,
 * https://docs.wave.com/webhook) :
 * <ul>
 *   <li>POST /v1/checkout/sessions, montant en chaîne de caractères, XOF sans décimales ;</li>
 *   <li>GET /v1/checkout/sessions/:id pour vérifier l'état (payment_status = succeeded) ;</li>
 *   <li>POST /v1/checkout/sessions/:id/refund ;</li>
 *   <li>webhooks signés par l'en-tête Wave-Signature (HMAC-SHA256, horodatage + corps brut).</li>
 * </ul>
 * <p>Inactif tant que la clé API, le secret de webhook et les URL de retour ne sont pas fournis.
 * Aucun environnement de test public n'est supposé : les essais réels se font avec un compte marchand.</p>
 */
@Component
public class WavePaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(WavePaymentProvider.class);

    private final AppProperties props;
    private final Clock clock;
    private final RestClient client;

    public WavePaymentProvider(AppProperties props, Clock clock, RestClient.Builder builder) {
        this.props = props;
        this.clock = clock;
        AppProperties.Wave w = props.payment().wave();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        if (w.timeout() != null) {
            factory.setConnectTimeout(w.timeout());
            factory.setReadTimeout(w.timeout());
        }
        this.client = builder.baseUrl(w.baseUrl() == null ? "https://api.wave.com" : w.baseUrl())
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + (w.apiKey() == null ? "" : w.apiKey()))
                .build();
    }

    @Override
    public ProviderCode code() {
        return ProviderCode.WAVE;
    }

    @Override
    public boolean available() {
        return props.payment().wave().configured();
    }

    @Override
    public String unavailableReason() {
        return "Wave non configuré : clé API marchande, secret de webhook et URL de retour HTTPS requis";
    }

    @Override
    public boolean simulated() {
        return false;
    }

    @Override
    public boolean supportsStatusCheck() {
        return true;
    }

    @Override
    public CheckoutSession createCheckout(CheckoutRequest request) throws ProviderException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", request.amount().toPlainString());
        body.put("currency", request.currency());
        body.put("client_reference", request.intentReference());
        body.put("success_url", props.payment().wave().successUrl());
        body.put("error_url", props.payment().wave().errorUrl());
        if (request.payerPhone() != null && request.payerPhone().startsWith("+")) {
            body.put("restrict_payer_mobile", request.payerPhone());
        }
        JsonNode node = call(() -> client.post().uri("/v1/checkout/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Json.write(body))
                .retrieve()
                .body(String.class));
        String id = node.path("id").asString(null);
        String url = node.path("wave_launch_url").asString(null);
        if (id == null || url == null) {
            throw new ProviderException("Réponse Wave incomplète", true, null);
        }
        String expires = node.path("when_expires").asString(null);
        return new CheckoutSession(id, url, expires == null ? Instant.now(clock).plusSeconds(1800) : Instant.parse(expires));
    }

    @Override
    public ProviderPaymentStatus fetchStatus(String sessionId) throws ProviderException {
        JsonNode node = call(() -> client.get().uri("/v1/checkout/sessions/{id}", sessionId).retrieve().body(String.class));
        String checkout = node.path("checkout_status").asString("");
        String payment = node.path("payment_status").asString("");
        String tx = node.path("transaction_id").asString(null);
        State state;
        if ("succeeded".equals(payment)) {
            state = State.REUSSI;
        } else if ("expired".equals(checkout)) {
            state = State.EXPIRE;
        } else if ("cancelled".equals(payment)) {
            state = State.ECHOUE;
        } else {
            state = State.EN_ATTENTE;
        }
        return new ProviderPaymentStatus(state, tx, node.path("last_payment_error").path("message").asString(null));
    }

    @Override
    public void refund(String sessionId) throws ProviderException {
        call(() -> {
            client.post().uri("/v1/checkout/sessions/{id}/refund", sessionId).retrieve().toBodilessEntity();
            return "{}";
        });
    }

    @Override
    public WebhookEvent parseWebhook(Map<String, String> headers, String rawBody) throws InvalidWebhookException {
        if (!available()) {
            throw new InvalidWebhookException("Wave non configuré");
        }
        WebhookSignature.verify(headers.get("wave-signature"), props.payment().wave().webhookSecret(), rawBody, clock);
        JsonNode node = Json.mapper().readTree(rawBody);
        String type = node.path("type").asString();
        JsonNode data = node.path("data");
        State state = switch (type) {
            case "checkout.session.completed" -> "succeeded".equals(data.path("payment_status").asString())
                    ? State.REUSSI : State.INCONNU;
            case "checkout.session.payment_failed" -> State.ECHOUE;
            default -> State.INCONNU;
        };
        return new WebhookEvent(node.path("id").asString(), type, data.path("id").asString(null),
                data.path("client_reference").asString(null), state, data.path("transaction_id").asString(null),
                data.path("last_payment_error").path("message").asString(null));
    }

    private interface Call {
        String run();
    }

    private JsonNode call(Call call) throws ProviderException {
        try {
            String body = call.run();
            return Json.mapper().readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            // Le corps de réponse n'est pas journalisé : il peut contenir des données de paiement.
            log.warn("Wave a répondu {}", status.value());
            throw new ProviderException("Wave a refusé la requête (HTTP " + status.value() + ")",
                    status.is5xxServerError() || status.value() == 429, e);
        } catch (ResourceAccessException e) {
            log.warn("Wave injoignable : {}", e.getClass().getSimpleName());
            throw new ProviderException("Wave est momentanément injoignable", true, e);
        }
    }
}
