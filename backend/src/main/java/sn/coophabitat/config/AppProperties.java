package sn.coophabitat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Configuration applicative, alimentée par les variables d'environnement (voir .env.example).
 * Aucun secret n'a de valeur par défaut utilisable en production.
 */
@ConfigurationProperties(prefix = "coophabitat")
public record AppProperties(
        DeploymentEnvironment environment,
        String frontendBaseUrl,
        Security security,
        Cors cors,
        Payment payment,
        Contribution contribution,
        Storage storage,
        Mail mail,
        Sms sms,
        Accounting accounting,
        Demo demo,
        Api api) {

    public enum DeploymentEnvironment { DEVELOPPEMENT, TEST, DEMONSTRATION, PRODUCTION }

    public record Security(String jwtSecret, Duration accessTokenTtl, Duration refreshTokenTtl, boolean cookieSecure,
                           int maxFailedAttempts, Duration lockDuration, Duration passwordResetTtl,
                           Duration invitationTtl, String bootstrapAdminEmail) {
    }

    public record Cors(List<String> allowedOrigins) {
    }

    public record Payment(boolean mockEnabled, String mockWebhookSecret, Duration intentTtl, BigDecimal minimumAmount,
                          int maxAdvancePeriods, Wave wave, SendFlash sendflash) {
    }

    public record Wave(boolean enabled, String baseUrl, String apiKey, String webhookSecret, String successUrl,
                       String errorUrl, Duration timeout) {
        public boolean configured() {
            return enabled && notBlank(apiKey) && notBlank(webhookSecret) && notBlank(successUrl) && notBlank(errorUrl);
        }
    }

    public record SendFlash(boolean enabled) {
    }

    public record Contribution(Set<String> generateForStatuses, int reminderDaysBefore, String reminderCron,
                               String penaltyCron, String generationCron) {
    }

    public record Storage(String path, long maxUploadBytes) {
    }

    public record Mail(boolean enabled, String from) {
    }

    public record Sms(String provider) {
    }

    public record Accounting(BigDecimal doubleApprovalThreshold) {
    }

    public record Demo(boolean enabled, String password) {
    }

    public record Api(boolean swaggerEnabled) {
    }

    static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    public boolean isProduction() {
        return environment == DeploymentEnvironment.PRODUCTION;
    }
}
