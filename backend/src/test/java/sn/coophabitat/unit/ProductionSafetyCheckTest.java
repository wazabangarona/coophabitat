package sn.coophabitat.unit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import sn.coophabitat.config.AppProperties;
import sn.coophabitat.config.ProductionSafetyCheck;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSafetyCheckTest {

    private static AppProperties props(AppProperties.DeploymentEnvironment env, boolean mock, boolean demo,
                                       boolean secureCookie, String secret) {
        return new AppProperties(env, "https://coop.example",
                new AppProperties.Security(secret, Duration.ofMinutes(15), Duration.ofDays(7), secureCookie, 5,
                        Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofDays(3), null),
                new AppProperties.Cors(List.of("https://coop.example")),
                new AppProperties.Payment(mock, "s", Duration.ofMinutes(30), BigDecimal.valueOf(100), 12,
                        new AppProperties.Wave(false, "https://api.wave.com", null, null, null, null, Duration.ofSeconds(10)),
                        new AppProperties.SendFlash(false)),
                new AppProperties.Contribution(Set.of("ACTIF"), 3, "0 0 8 * * *", "0 30 1 * * *", "0 0 1 * * *"),
                new AppProperties.Storage("/tmp", 1000), new AppProperties.Mail(false, "x@y"),
                new AppProperties.Sms("none"), new AppProperties.Accounting(BigDecimal.valueOf(500000)),
                new AppProperties.Demo(demo, null), new AppProperties.Api(false));
    }

    private static final String STRONG = "f3a9c1e7b5d2a8f6c4e0b9d7a5c3e1f0-prod";

    @Test
    void productionRefusesSimulatedPayments() {
        var check = new ProductionSafetyCheck(props(AppProperties.DeploymentEnvironment.PRODUCTION, true, false, true, STRONG),
                new MockEnvironment());
        assertThatThrownBy(check::afterPropertiesSet).hasMessageContaining("simulé");
    }

    @Test
    void productionRefusesDemoDataAndInsecureCookies() {
        assertThatThrownBy(new ProductionSafetyCheck(props(AppProperties.DeploymentEnvironment.PRODUCTION, false, true, true, STRONG),
                new MockEnvironment())::afterPropertiesSet).hasMessageContaining("démonstration");
        assertThatThrownBy(new ProductionSafetyCheck(props(AppProperties.DeploymentEnvironment.PRODUCTION, false, false, false, STRONG),
                new MockEnvironment())::afterPropertiesSet).hasMessageContaining("Secure");
    }

    @Test
    void weakSecretIsRefusedEverywhere() {
        assertThatThrownBy(new ProductionSafetyCheck(props(AppProperties.DeploymentEnvironment.DEVELOPPEMENT, true, false, false, "court"),
                new MockEnvironment())::afterPropertiesSet).hasMessageContaining("32");
    }

    @Test
    void validProductionConfigurationStarts() {
        assertThatCode(new ProductionSafetyCheck(props(AppProperties.DeploymentEnvironment.PRODUCTION, false, false, true, STRONG),
                new MockEnvironment())::afterPropertiesSet).doesNotThrowAnyException();
    }
}
