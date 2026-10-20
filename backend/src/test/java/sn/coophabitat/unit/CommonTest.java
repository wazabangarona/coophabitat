package sn.coophabitat.unit;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import sn.coophabitat.common.AmountInWords;
import sn.coophabitat.common.BusinessException;
import sn.coophabitat.common.Money;
import sn.coophabitat.member.MemberStatus;
import sn.coophabitat.payment.provider.PaymentProvider;
import sn.coophabitat.payment.provider.WebhookSignature;
import sn.coophabitat.property.LotStatus;
import sn.coophabitat.reporting.ExcelExporter;
import sn.coophabitat.security.PasswordPolicy;
import sn.coophabitat.security.RateLimiter;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommonTest {

    @Test
    void amountsInWordsFollowFrenchRules() {
        assertThat(AmountInWords.toWords(0)).isEqualTo("zéro");
        assertThat(AmountInWords.toWords(21)).isEqualTo("vingt et un");
        assertThat(AmountInWords.toWords(80)).isEqualTo("quatre-vingts");
        assertThat(AmountInWords.toWords(25000)).isEqualTo("vingt-cinq mille");
        assertThat(AmountInWords.toWords(200)).isEqualTo("deux cents");
        assertThat(AmountInWords.toWords(1_000_000)).isEqualTo("un million");
    }

    @Test
    void moneyRejectsFractionsAndNegatives() {
        assertThat(Money.requirePositiveXof(new BigDecimal("2500.00"), "x")).isEqualByComparingTo("2500");
        assertThatThrownBy(() -> Money.requirePositiveXof(new BigDecimal("10.5"), "x")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> Money.requirePositiveXof(BigDecimal.ZERO, "x")).isInstanceOf(BusinessException.class);
        assertThat(Money.format(BigDecimal.valueOf(25000))).endsWith("FCFA").contains("25");
    }

    @Test
    void webhookSignatureAcceptsValidAndRejectsTamperedOrStale() throws Exception {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC);
        String body = "{\"id\":\"evt\"}";
        String header = WebhookSignature.header("secret", 1_800_000_000L, body);
        WebhookSignature.verify(header, "secret", body, clock);
        assertThatThrownBy(() -> WebhookSignature.verify(header, "secret", body + " ", clock))
                .isInstanceOf(PaymentProvider.InvalidWebhookException.class);
        assertThatThrownBy(() -> WebhookSignature.verify(header, "autre", body, clock))
                .isInstanceOf(PaymentProvider.InvalidWebhookException.class);
        String stale = WebhookSignature.header("secret", 1_800_000_000L - 301, body);
        assertThatThrownBy(() -> WebhookSignature.verify(stale, "secret", body, clock))
                .isInstanceOf(PaymentProvider.InvalidWebhookException.class);
        assertThatThrownBy(() -> WebhookSignature.verify(null, "secret", body, clock))
                .isInstanceOf(PaymentProvider.InvalidWebhookException.class);
    }

    @Test
    void passwordPolicy() {
        PasswordPolicy p = new PasswordPolicy();
        p.validate("Une-phrase-de-passe-solide", "awa@exemple.sn");
        assertThatThrownBy(() -> p.validate("court", null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> p.validate("password1234", null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> p.validate("aaaaaaaaaaaaaa", null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> p.validate("moussa-2026-secret", "moussa@exemple.sn")).isInstanceOf(BusinessException.class);
    }

    @Test
    void rateLimiterBlocksAfterLimit() {
        RateLimiter limiter = new RateLimiter(Clock.systemUTC());
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("ip", 5, 60_000)).isTrue();
        }
        assertThat(limiter.tryAcquire("ip", 5, 60_000)).isFalse();
        assertThat(limiter.tryAcquire("autre-ip", 5, 60_000)).isTrue();
    }

    @Test
    void statusTransitions() {
        assertThat(MemberStatus.RADIE.allowedTargets()).isEmpty();
        assertThat(MemberStatus.EN_ATTENTE.allowedTargets()).containsExactlyInAnyOrder(MemberStatus.ACTIF, MemberStatus.REFUSE);
        assertThat(MemberStatus.REFUSE.allowedTargets()).containsExactly(MemberStatus.EN_ATTENTE);
        assertThat(LotStatus.ATTRIBUE.manualTargets()).isEmpty();
        assertThat(LotStatus.DISPONIBLE.manualTargets()).doesNotContain(LotStatus.ATTRIBUE, LotStatus.RESERVE);
    }

    @Test
    void excelExportNeutralisesFormulas() throws Exception {
        byte[] xlsx = ExcelExporter.export(List.of(new ExcelExporter.Sheet("Test", List.of("note"), List.of("A", "B"),
                List.of(List.of("=HYPERLINK(\"http://x\")", BigDecimal.valueOf(1000))))));
        try (var wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheetAt(0);
            String cell = null;
            for (var row : sheet) {
                if (row.getCell(0) != null && row.getCell(0).getCellType() == org.apache.poi.ss.usermodel.CellType.STRING
                        && row.getCell(0).getStringCellValue().contains("HYPERLINK")) {
                    cell = row.getCell(0).getStringCellValue();
                    assertThat(row.getCell(0).getCellType()).isNotEqualTo(org.apache.poi.ss.usermodel.CellType.FORMULA);
                }
            }
            assertThat(cell).isNotNull().doesNotStartWith("=");
        }
    }
}
