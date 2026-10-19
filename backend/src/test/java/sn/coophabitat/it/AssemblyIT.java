package sn.coophabitat.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import sn.coophabitat.assembly.*;
import sn.coophabitat.assembly.AssemblyDtos.*;
import sn.coophabitat.common.BusinessException;
import sn.coophabitat.contribution.PenaltyMethod;
import sn.coophabitat.member.Member;
import sn.coophabitat.security.CurrentUser;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Assemblées : convocation, présences, résolutions, comptes rendus versionnés. */
class AssemblyIT extends IntegrationTest {

    @Autowired AssemblyService service;

    @Test
    void assemblyLifecycle() throws Exception {
        var plan = fx.activePlan(BigDecimal.valueOf(1_000), LocalDate.now().withDayOfMonth(1).plusMonths(1), 0,
                PenaltyMethod.AUCUNE, BigDecimal.ZERO);
        Member a = fx.activeMember(plan.getId(), LocalDate.now().minusYears(1));
        Member b = fx.activeMember(plan.getId(), LocalDate.now().minusYears(1));
        String memberToken = fx.token(fx.memberAccount(a));
        CurrentUser sec = fx.actAs(fx.user(null, "SECRETAIRE"));

        GeneralAssembly ag = service.create(new AssemblyRequest("AG de test " + Fixtures.next(), GeneralAssembly.Type.ORDINAIRE,
                Instant.now().plus(3, ChronoUnit.DAYS), "Salle", GeneralAssembly.Modality.MIXTE, "Moitié des membres", null), sec);
        mvc.perform(get("/api/v1/assemblies/" + ag.getId()).header("Authorization", bearer(memberToken)))
                .andExpect(status().isNotFound());
        assertThatThrownBy(() -> service.convene(ag.getId())).hasMessageContaining("ordre du jour");
        service.setAgenda(ag.getId(), List.of(new AgendaEntry("Comptes", null), new AgendaEntry("Budget", "Vote du budget")));
        service.convene(ag.getId());
        assertThatThrownBy(() -> service.setAgenda(ag.getId(), List.of(new AgendaEntry("Ajout tardif", null))))
                .isInstanceOf(BusinessException.class);
        mvc.perform(get("/api/v1/assemblies/" + ag.getId()).header("Authorization", bearer(memberToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.agenda.length()").value(2))
                .andExpect(jsonPath("$.convocationDocumentId").isNumber());
        assertThatThrownBy(() -> service.markHeld(ag.getId())).hasMessageContaining("avant sa date");

        jdbc.update("UPDATE general_assembly SET scheduled_at = now() - interval '1 hour' WHERE id = ?", ag.getId());
        service.recordAttendance(ag.getId(), List.of(new AttendanceEntry(a.getId(), Attendance.Status.PRESENT, null),
                new AttendanceEntry(b.getId(), Attendance.Status.REPRESENTE, a.getId())), sec);
        assertThatThrownBy(() -> service.recordAttendance(ag.getId(), List.of(
                new AttendanceEntry(b.getId(), Attendance.Status.REPRESENTE, null)), sec)).isInstanceOf(BusinessException.class);
        service.markHeld(ag.getId());
        QuorumInfo q = service.quorum(ag.getId());
        assertThat(q.present()).isEqualTo(1);
        assertThat(q.represented()).isEqualTo(1);

        service.recordResolution(ag.getId(), new ResolutionRequest(null, "R1", "Les comptes sont approuvés.", 10, 1, 2,
                Resolution.Outcome.ADOPTEE), sec);
        assertThatThrownBy(() -> service.recordResolution(ag.getId(), new ResolutionRequest(null, "R1", "Doublon", 0, 0, 0,
                Resolution.Outcome.REJETEE), sec)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.close(ag.getId())).hasMessageContaining("compte rendu");

        AssemblyMinutes v1 = service.draftMinutes(ag.getId(), "Première version du compte rendu.\n\nLa séance est ouverte à 10 h. "
                + "Les membres présents approuvent les comptes.", sec);
        service.publishMinutes(ag.getId(), v1.getId());
        AssemblyMinutes v2 = service.draftMinutes(ag.getId(), "Seconde version corrigée du compte rendu : l'heure d'ouverture "
                + "était 10 h 30 et non 10 h.", sec);
        service.publishMinutes(ag.getId(), v2.getId());
        var versions = service.minutesOf(ag.getId(), true);
        assertThat(versions).extracting(MinutesResponse::status)
                .containsExactly(AssemblyMinutes.Status.REMPLACE, AssemblyMinutes.Status.PUBLIE);
        assertThat(service.minutesOf(ag.getId(), false)).hasSize(1);
        service.close(ag.getId());
        assertThat(service.get(ag.getId(), true).getStatus()).isEqualTo(GeneralAssembly.Status.CLOTUREE);
        mvc.perform(get("/api/v1/assemblies/" + ag.getId() + "/minutes").header("Authorization", bearer(memberToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE dedup_key LIKE ?", Long.class,
                "CONVOCATION:" + ag.getId() + ":%")).isPositive();
    }

    @Test
    void convenedAssemblyCanBeCancelledWithAReason() {
        CurrentUser sec = fx.actAs(fx.user(null, "SECRETAIRE"));
        GeneralAssembly ag = service.create(new AssemblyRequest("AG annulée " + Fixtures.next(), GeneralAssembly.Type.EXTRAORDINAIRE,
                Instant.now().plus(10, ChronoUnit.DAYS), null, GeneralAssembly.Modality.EN_LIGNE, null, null), sec);
        service.setAgenda(ag.getId(), List.of(new AgendaEntry("Point unique", null)));
        service.convene(ag.getId());
        service.cancel(ag.getId(), "Quorum manifestement impossible à réunir");
        assertThat(service.get(ag.getId(), false).getStatus()).isEqualTo(GeneralAssembly.Status.ANNULEE);
        assertThatThrownBy(() -> service.markHeld(ag.getId())).isInstanceOf(BusinessException.class);
    }
}
