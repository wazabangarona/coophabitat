package sn.coophabitat.payment.provider;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Vérification d'en-têtes de signature au format « t=&lt;horodatage&gt;,v1=&lt;hmac hex&gt;[,v1=…] » :
 * HMAC-SHA256(secret, horodatage + corps brut), tolérance de 5 minutes contre le rejeu.
 * Format documenté par Wave (https://docs.wave.com/webhook), réutilisé par le simulateur.
 */
public final class WebhookSignature {

    public static final long TOLERANCE_SECONDS = 300;

    private WebhookSignature() {
    }

    public static String sign(String secret, long timestamp, String body) {
        return HexFormat.of().formatHex(hmac(secret, timestamp + body));
    }

    public static String header(String secret, long timestamp, String body) {
        return "t=" + timestamp + ",v1=" + sign(secret, timestamp, body);
    }

    public static void verify(String header, String secret, String rawBody, Clock clock)
            throws PaymentProvider.InvalidWebhookException {
        if (header == null || header.isBlank()) {
            throw new PaymentProvider.InvalidWebhookException("En-tête de signature absent");
        }
        Long timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if ("t".equals(kv[0])) {
                try {
                    timestamp = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    throw new PaymentProvider.InvalidWebhookException("Horodatage invalide");
                }
            } else if ("v1".equals(kv[0])) {
                signatures.add(kv[1]);
            }
        }
        if (timestamp == null || signatures.isEmpty()) {
            throw new PaymentProvider.InvalidWebhookException("En-tête de signature mal formé");
        }
        long now = clock.instant().getEpochSecond();
        if (Math.abs(now - timestamp) > TOLERANCE_SECONDS) {
            throw new PaymentProvider.InvalidWebhookException("Horodatage hors tolérance (rejeu possible)");
        }
        byte[] expected = hmac(secret, timestamp + rawBody);
        for (String candidate : signatures) {
            try {
                if (MessageDigest.isEqual(expected, HexFormat.of().parseHex(candidate))) {
                    return;
                }
            } catch (IllegalArgumentException ignored) {
                // signature non hexadécimale : ignorée
            }
        }
        throw new PaymentProvider.InvalidWebhookException("Signature invalide");
    }

    private static byte[] hmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
