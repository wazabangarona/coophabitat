package sn.coophabitat.payment;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Réception des notifications des fournisseurs. Le corps brut est conservé tel quel pour vérifier la signature
 * (une désérialisation préalable invaliderait la signature HMAC).
 */
@RestController
@RequestMapping("/api/v1/payments/webhooks")
@Tag(name = "Paiements — notifications fournisseurs")
public class WebhookController {

    private final PaymentService service;

    public WebhookController(PaymentService service) {
        this.service = service;
    }

    @PostMapping(value = "/wave", consumes = "application/json")
    @Operation(summary = "Notification Wave signée (en-tête Wave-Signature)")
    public PaymentDtos.WebhookResult wave(@RequestBody String rawBody, HttpServletRequest request) {
        return service.handleWebhook(ProviderCode.WAVE, headers(request), rawBody);
    }

    @PostMapping(value = "/mock", consumes = "application/json")
    @Operation(summary = "SIMULATION — notification signée du fournisseur fictif (désactivée hors développement)")
    public PaymentDtos.WebhookResult mock(@RequestBody String rawBody, HttpServletRequest request) {
        return service.handleWebhook(ProviderCode.MOCK, headers(request), rawBody);
    }

    private static Map<String, String> headers(HttpServletRequest request) {
        Map<String, String> map = new HashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            map.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        return map;
    }
}
