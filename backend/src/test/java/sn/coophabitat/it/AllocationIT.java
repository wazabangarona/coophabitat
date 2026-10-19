package sn.coophabitat.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import sn.coophabitat.allocation.*;
import sn.coophabitat.allocation.AllocationDtos.ApprovalRequest;
import sn.coophabitat.allocation.AllocationDtos.CampaignRequest;
import sn.coophabitat.common.BusinessException;
import sn.coophabitat.contribution.PenaltyMethod;
import sn.coophabitat.member.Member;
import sn.coophabitat.property.LotStatus;
import sn.coophabitat.property.PropertyLot;
import sn.coophabitat.property.PropertyLotRepository;
import sn.coophabitat.security.CurrentUser;
import sn.coophabitat.security.UserAccount;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Attribution : étapes obligatoires, validations distinctes, tirage unique et vérifiable, absence de double attribution. */
class AllocationIT extends IntegrationTest {

    @Autowired AllocationService service;
    @Autowired AllocationResultRepository results;
    @Autowired PropertyLotRepository lots;

    record Setup(AllocationCampaign campaign, List<Member> candidates, List<PropertyLot> lots, CurrentUser secretary,
                 CurrentUser president, CurrentUser secondApprover) {
    }

    private Setup prepare(int candidateCount, int lotCount) {
        var plan = fx.activePlan(BigDecimal.valueOf(1_000), LocalDate.now().withDayOfMonth(1).plusMonths(1), 0,
                PenaltyMethod.AUCUNE, BigDecimal.ZERO);
        List<Member> members = new ArrayList<>();
        for (int i = 0; i < candidateCount; i++) {
            members.add(fx.activeMember(plan.getId(), LocalDate.now().minusYears(2)));
        }
        List<PropertyLot> l = new ArrayList<>();
        for (int i = 0; i < lotCount; i++) {
            l.add(fx.availableLot());
        }
        UserAccount sec = fx.user(null, "SECRETAIRE");
        CurrentUser s = fx.actAs(sec);
        AllocationCampaign c = service.create(new CampaignRequest("Procédure de test " + Fixtures.next(), null,
                AllocationMethod.TIRAGE_AU_SORT, "Membre actif depuis 6 mois", "Tirage au sort", 6, true, BigDecimal.ZERO,
                List.of("ORDINAIRE"), LocalDate.now(), Instant.now().plus(5, ChronoUnit.DAYS), 2, null, null), s);
        service.setLots(c.getId(), l.stream().map(PropertyLot::getId).toList());
        service.publishCriteria(c.getId());
        service.openApplications(c.getId());
        for (Member m : members) {
            service.apply(c.getId(), m.getId());
        }
        service.closeApplications(c.getId());
        service.publishList(c.getId());
        return new Setup(service.get(c.getId()), members, l, s, fx.current(fx.user(null, "PRESIDENT")),
                fx.current(fx.user(null, "PRESIDENT")));
    }

    private void validateList(Setup st) {
        fx.actAs(fx.user(null, "PRESIDENT"));
        service.approve(st.campaign().getId(), new ApprovalRequest(AllocationApproval.Step.VALIDATION_LISTE,
                AllocationApproval.Decision.APPROUVE, null, null), st.president());
        service.approve(st.campaign().getId(), new ApprovalRequest(AllocationApproval.Step.VALIDATION_LISTE,
                AllocationApproval.Decision.APPROUVE, null, null), st.secondApprover());
    }

    @Test
    void fullProcedureProducesAVerifiableDrawExecutedOnlyOnce() throws Exception {
        Setup st = prepare(5, 2);
        Long id = st.campaign().getId();
        assertThat(st.campaign().getSeedCommitment()).hasSize(64);
        assertThat(service.toResponse(st.campaign()).revealedSeed()).isNull();
        assertThat(st.lots()).allMatch(l -> lots.findById(l.getId()).orElseThrow().getStatus() == LotStatus.RESERVE);

        assertThatThrownBy(() -> service.executeDraw(id, "x", st.president())).isInstanceOf(BusinessException.class);
        validateList(st);
        assertThat(service.get(id).getStatus()).isEqualTo(CampaignStatus.VALIDEE);

        service.executeDraw(id, "Entropie publique de test", st.president());
        assertThatThrownBy(() -> service.executeDraw(id, "autre", st.president()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("déjà été exécuté");

        List<AllocationResult> res = results.findByCampaignIdOrderByDrawRankAscIdAsc(id);
        assertThat(res).hasSize(5);
        assertThat(res.stream().filter(r -> r.getStatus() == AllocationResult.Status.PROVISOIRE)).hasSize(2);
        assertThat(res.stream().filter(r -> r.getStatus() == AllocationResult.Status.LISTE_ATTENTE)).hasSize(3);

        var verification = service.verify(id);
        assertThat(verification.valid()).isTrue();
        assertThat(verification.recomputedOrder()).isEqualTo(verification.recordedOrder());

        // Validation du PV par deux responsables distincts : résultats définitifs et lots attribués.
        service.approve(id, new ApprovalRequest(AllocationApproval.Step.VALIDATION_PV, AllocationApproval.Decision.APPROUVE,
                null, null), st.president());
        service.approve(id, new ApprovalRequest(AllocationApproval.Step.VALIDATION_PV, AllocationApproval.Decision.APPROUVE,
                null, null), st.secondApprover());
        assertThat(service.get(id).getStatus()).isEqualTo(CampaignStatus.PV_VALIDE);
        assertThat(st.lots()).allMatch(l -> lots.findById(l.getId()).orElseThrow().getStatus() == LotStatus.ATTRIBUE);
        service.publishResults(id);

        // Vue d'un membre : résultats publiés sans les noms.
        String token = fx.token(fx.memberAccount(st.candidates().get(0)));
        String body = mvc.perform(get("/api/v1/allocations/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains(st.candidates().get(0).getMemberNumber()).doesNotContain(st.candidates().get(1).getFirstName() + " ");
        mvc.perform(get("/api/v1/allocations/" + id + "/candidates").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    void sameApproverCannotCountTwiceAndCandidatesCannotValidate() {
        Setup st = prepare(3, 1);
        Long id = st.campaign().getId();
        service.approve(id, new ApprovalRequest(AllocationApproval.Step.VALIDATION_LISTE, AllocationApproval.Decision.APPROUVE,
                null, null), st.president());
        assertThatThrownBy(() -> service.approve(id, new ApprovalRequest(AllocationApproval.Step.VALIDATION_LISTE,
                AllocationApproval.Decision.APPROUVE, null, null), st.president())).isInstanceOf(BusinessException.class);
        // Un membre du bureau qui est aussi candidat est en conflit d'intérêts.
        UserAccount conflicted = fx.user(st.candidates().get(0).getId(), "PRESIDENT", "MEMBRE");
        CurrentUser cu = fx.current(conflicted);
        assertThatThrownBy(() -> service.approve(id, new ApprovalRequest(AllocationApproval.Step.VALIDATION_LISTE,
                AllocationApproval.Decision.APPROUVE, null, null), cu)).hasMessageContaining("conflit");
        assertThat(service.get(id).getStatus()).isEqualTo(CampaignStatus.LISTE_PUBLIEE);
    }

    @Test
    void aLotCannotBeEngagedInTwoProceduresNorHaveTwoActiveResults() {
        Setup st = prepare(2, 1);
        PropertyLot lot = st.lots().get(0);
        CurrentUser s = fx.actAs(fx.user(null, "SECRETAIRE"));
        AllocationCampaign other = service.create(new CampaignRequest("Autre procédure " + Fixtures.next(), null,
                AllocationMethod.TIRAGE_AU_SORT, "c", "r", 0, false, BigDecimal.ZERO, List.of("ORDINAIRE"),
                LocalDate.now(), null, 1, null, null), s);
        assertThatThrownBy(() -> service.setLots(other.getId(), List.of(lot.getId()))).hasMessageContaining("disponible");

        validateList(st);
        service.executeDraw(st.campaign().getId(), "", st.president());
        AllocationResult waiting = results.findByCampaignIdOrderByDrawRankAscIdAsc(st.campaign().getId()).stream()
                .filter(r -> r.getLotId() == null).findFirst().orElseThrow();
        // Garde de la base : un second résultat actif pour le même lot est impossible.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO allocation_result (campaign_id, candidate_id, lot_id, status, justification)
                VALUES (?, ?, ?, 'PROVISOIRE', 'double')""", st.campaign().getId(), waiting.getCandidateId(), lot.getId()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ux_result_active_lot");
    }

    @Test
    void formalCancellationReleasesTheLot() {
        Setup st = prepare(2, 1);
        validateList(st);
        service.executeDraw(st.campaign().getId(), "", st.president());
        AllocationResult r = results.findByCampaignIdOrderByDrawRankAscIdAsc(st.campaign().getId()).stream()
                .filter(x -> x.getLotId() != null).findFirst().orElseThrow();
        service.requestResultCancellation(r.getId(), "Erreur matérielle constatée en séance", st.president());
        assertThat(results.findById(r.getId()).orElseThrow().getStatus()).isEqualTo(AllocationResult.Status.PROVISOIRE);
        service.requestResultCancellation(r.getId(), "Erreur matérielle constatée en séance", st.secondApprover());
        assertThat(results.findById(r.getId()).orElseThrow().getStatus()).isEqualTo(AllocationResult.Status.ANNULE);
        assertThat(lots.findById(r.getLotId()).orElseThrow().getStatus()).isEqualTo(LotStatus.DISPONIBLE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lot_status_history WHERE lot_id = ?", Long.class, r.getLotId()))
                .isGreaterThanOrEqualTo(4);
    }

    @Test
    void ineligibleCandidatesAreExcludedWithAReason() {
        var plan = fx.activePlan(BigDecimal.valueOf(1_000), LocalDate.now().withDayOfMonth(1).plusMonths(1), 0,
                PenaltyMethod.AUCUNE, BigDecimal.ZERO);
        Member senior = fx.activeMember(plan.getId(), LocalDate.now().minusYears(2));
        Member junior = fx.activeMember(plan.getId(), LocalDate.now().minusMonths(1));
        PropertyLot lot = fx.availableLot();
        CurrentUser s = fx.actAs(fx.user(null, "SECRETAIRE"));
        AllocationCampaign c = service.create(new CampaignRequest("Éligibilité " + Fixtures.next(), null,
                AllocationMethod.TIRAGE_AU_SORT, "6 mois d'ancienneté", "Tirage", 6, true, BigDecimal.ZERO,
                List.of("ORDINAIRE"), LocalDate.now(), null, 1, null, null), s);
        service.setLots(c.getId(), List.of(lot.getId()));
        service.publishCriteria(c.getId());
        service.openApplications(c.getId());
        service.apply(c.getId(), senior.getId());
        service.apply(c.getId(), junior.getId());
        assertThatThrownBy(() -> service.apply(c.getId(), senior.getId())).isInstanceOf(BusinessException.class);
        service.closeApplications(c.getId());
        var cands = service.candidateResponses(c.getId());
        assertThat(cands).filteredOn(x -> x.memberId().equals(senior.getId()))
                .allMatch(x -> x.eligibilityStatus() == AllocationCandidate.Eligibility.ELIGIBLE);
        assertThat(cands).filteredOn(x -> x.memberId().equals(junior.getId()))
                .allMatch(x -> x.eligibilityStatus() == AllocationCandidate.Eligibility.NON_ELIGIBLE
                        && x.eligibilityReason().contains("ancienneté"));
    }
}
