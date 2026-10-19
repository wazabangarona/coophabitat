package sn.coophabitat.it;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Le jeu de démonstration passe par les services réels et reste marqué comme fictif. */
@TestPropertySource(properties = {"coophabitat.demo.enabled=true", "coophabitat.demo.password=Mot-de-passe-demo-tests"})
class DemoDataIT extends IntegrationTest {

    @Test
    void demoDataIsLoadedAndFlagged() {
        assertThat(jdbc.queryForObject("SELECT is_demo FROM coop_settings", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_account WHERE email LIKE '%@demo.coophabitat.invalid' AND NOT is_demo",
                Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member WHERE is_demo", Long.class)).isGreaterThanOrEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transaction", Long.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM allocation_campaign WHERE is_demo", Long.class)).isEqualTo(2);
        // Profils exigés : lots attribués par une procédure terminée, paiements simulés confirmé et en attente,
        // rapport financier publié.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM property_lot WHERE is_demo AND status = 'ATTRIBUE'", Long.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM allocation_campaign WHERE is_demo AND seed_revealed",
                Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_intent i JOIN member m ON m.id = i.member_id WHERE m.is_demo AND i.simulated AND i.status = 'CONFIRME'", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_intent i JOIN member m ON m.id = i.member_id WHERE m.is_demo AND i.simulated AND i.status = 'EN_ATTENTE'", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report r JOIN user_account u ON u.id = r.published_by WHERE u.is_demo AND r.status = 'PUBLIE'", Long.class)).isEqualTo(1);
        // Les mots de passe ne sont jamais stockés en clair.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_account WHERE password_hash = 'Mot-de-passe-demo-tests'",
                Long.class)).isZero();
    }
}
