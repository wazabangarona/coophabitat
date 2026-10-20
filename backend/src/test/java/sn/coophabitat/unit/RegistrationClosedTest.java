package sn.coophabitat.unit;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import sn.coophabitat.common.BusinessException;
import sn.coophabitat.member.RegistrationService;
import sn.coophabitat.member.RegistrationService.RegistrationRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Sans COOPHABITAT_PUBLIC_REGISTRATION=true, aucune demande d'adhésion en ligne n'est acceptée. */
class RegistrationClosedTest {

    @Test
    void registrationIsClosedByDefault() {
        RegistrationService closed = new RegistrationService(false, null, null, null, null, null, null, null, null, null);
        assertThat(closed.enabled()).isFalse();
        assertThatThrownBy(() -> closed.register(new RegistrationRequest("Awa", "Diop", "awa@test.invalid", null, null,
                "phrase-secrete-assez-longue", true)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }
}
