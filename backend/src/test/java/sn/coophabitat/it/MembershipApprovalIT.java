package sn.coophabitat.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import sn.coophabitat.contribution.PenaltyMethod;
import sn.coophabitat.member.Member;
import sn.coophabitat.member.MemberDtos.ApprovalRequest;
import sn.coophabitat.member.MemberDtos.CreateMemberRequest;
import sn.coophabitat.member.MemberService;
import sn.coophabitat.member.MemberStatus;
import sn.coophabitat.member.MemberType;
import sn.coophabitat.security.UserAccount;
import sn.coophabitat.security.UserAccountRepository;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Demandes d'adhésion : approbation en une décision (plan + activation), refus motivé, garde-fous et mise en route. */
class MembershipApprovalIT extends IntegrationTest {

    @Autowired
    MemberService members;

    @Autowired
    UserAccountRepository users;

    /** Dossier en attente, sans plan, avec un compte de connexion (comme une demande déposée en ligne). */
    private Member applicant(String... roles) {
        UserAccount sec = fx.user(null, "SECRETAIRE");
        var created = members.create(new CreateMemberRequest("Demandeur" + Fixtures.next(), "Test", null, null, null,
                "Ville", LocalDate.now(), MemberType.ORDINAIRE, null, null, false), fx.actAs(sec));
        String[] r = roles.length == 0 ? new String[]{"MEMBRE"} : roles;
        fx.user(created.member().id(), r);
        return members.get(created.member().id());
    }

    private String json(String s) {
        return s.replace('\'', '"');
    }

    @Test
    void approvalAssignsThePlanAndActivatesInOneTracedDecision() throws Exception {
        var plan = fx.activePlan(BigDecimal.valueOf(10_000), LocalDate.now(), 5, PenaltyMethod.AUCUNE, BigDecimal.ZERO);
        Member m = applicant();
        String sec = fx.token(fx.user(null, "SECRETAIRE"));

        mvc.perform(get("/api/v1/members/applications").header("Authorization", bearer(sec)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canDecide").value(true))
                .andExpect(jsonPath("$.items[?(@.id == " + m.getId() + ")].hasAccount").value(true));

        // Sans plan affecté ni choisi, l'approbation est refusée.
        mvc.perform(post("/api/v1/members/" + m.getId() + "/approve").header("Authorization", bearer(sec))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("PLAN_REQUIS"));

        mvc.perform(post("/api/v1/members/" + m.getId() + "/approve").header("Authorization", bearer(sec))
                        .contentType(MediaType.APPLICATION_JSON).content(json("{'planId':" + plan.getId() + "}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIF"))
                .andExpect(jsonPath("$.contributionPlanId").value(plan.getId()));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'ADHESION_APPROUVEE' AND entity_id = ?",
                Long.class, String.valueOf(m.getId()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE member_id = ? AND template_code = 'STATUT_ADHESION_ACTIF'",
                Long.class, m.getId())).isPositive();
        mvc.perform(get("/api/v1/members/" + m.getId() + "/history").header("Authorization", bearer(sec)))
                .andExpect(jsonPath("$[?(@.toStatus == 'ACTIF')].reason").value("Demande d'adhésion approuvée par le bureau"));

        // Une demande déjà traitée ne peut pas l'être une seconde fois.
        mvc.perform(post("/api/v1/members/" + m.getId() + "/approve").header("Authorization", bearer(sec))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("DEMANDE_DEJA_TRAITEE"));
    }

    @Test
    void rejectionNeedsAReasonDisablesTheAccountAndCanBeReconsidered() throws Exception {
        Member m = applicant();
        String president = fx.token(fx.user(null, "PRESIDENT"));
        mvc.perform(post("/api/v1/members/" + m.getId() + "/reject").header("Authorization", bearer(president))
                        .contentType(MediaType.APPLICATION_JSON).content(json("{'reason':''}")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/members/" + m.getId() + "/reject").header("Authorization", bearer(president))
                        .contentType(MediaType.APPLICATION_JSON).content(json("{'reason':'Pièces justificatives manquantes'}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REFUSE"));
        assertThat(users.findByMemberId(m.getId()).orElseThrow().isEnabled()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE member_id = ? AND template_code = 'STATUT_ADHESION_REFUSE'",
                Long.class, m.getId())).isPositive();

        // Réexamen : retour en attente, le compte est rouvert.
        mvc.perform(post("/api/v1/members/" + m.getId() + "/status").header("Authorization", bearer(president))
                        .contentType(MediaType.APPLICATION_JSON).content(json("{'status':'EN_ATTENTE','reason':'Pièces reçues, réexamen'}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EN_ATTENTE"));
        assertThat(users.findByMemberId(m.getId()).orElseThrow().isEnabled()).isTrue();
    }

    @Test
    void onlyAuthorizedRolesDecideAndNobodyApprovesTheirOwnApplication() throws Exception {
        var plan = fx.activePlan(BigDecimal.valueOf(10_000), LocalDate.now(), 5, PenaltyMethod.AUCUNE, BigDecimal.ZERO);
        Member m = applicant();
        String treasurer = fx.token(fx.user(null, "TRESORIER"));
        mvc.perform(post("/api/v1/members/" + m.getId() + "/approve").header("Authorization", bearer(treasurer))
                        .contentType(MediaType.APPLICATION_JSON).content(json("{'planId':" + plan.getId() + "}")))
                .andExpect(status().isForbidden());
        // La trésorerie peut préparer le dossier en affectant le plan ; l'approbation le reprend sans le redemander.
        mvc.perform(post("/api/v1/members/" + m.getId() + "/plan").header("Authorization", bearer(treasurer))
                        .contentType(MediaType.APPLICATION_JSON).content(json("{'planId':" + plan.getId() + "}")))
                .andExpect(status().isOk());

        // Un président dont l'adhésion est en attente ne la valide pas lui-même quand un autre responsable est en place.
        fx.user(null, "SECRETAIRE");
        Member self = applicant("MEMBRE", "PRESIDENT");
        String selfToken = fx.token(users.findByMemberId(self.getId()).orElseThrow());
        mvc.perform(post("/api/v1/members/" + self.getId() + "/approve").header("Authorization", bearer(selfToken))
                        .contentType(MediaType.APPLICATION_JSON).content(json("{'planId':" + plan.getId() + "}")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/members/" + self.getId() + "/status").header("Authorization", bearer(selfToken))
                        .contentType(MediaType.APPLICATION_JSON).content(json("{'status':'ACTIF','reason':'Je me valide'}")))
                .andExpect(status().isForbidden());
        // Il peut en revanche valider les autres.
        mvc.perform(post("/api/v1/members/" + m.getId() + "/approve").header("Authorization", bearer(selfToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIF"));
    }

    /** Installation neuve : aucun responsable habilité n'est en place, le premier président valide sa propre adhésion. */
    @Test
    @Transactional
    void theFirstBureauCanValidateItselfWhenNoOtherApproverExists() {
        var plan = fx.activePlan(BigDecimal.valueOf(10_000), LocalDate.now(), 5, PenaltyMethod.AUCUNE, BigDecimal.ZERO);
        Member self = applicant("MEMBRE", "PRESIDENT");
        UserAccount account = users.findByMemberId(self.getId()).orElseThrow();
        // Simule une installation neuve (annulé en fin de test) : aucun autre compte de présidence ou de secrétariat actif.
        jdbc.update("UPDATE user_account SET enabled = FALSE WHERE id <> ? AND id IN "
                + "(SELECT user_id FROM user_role WHERE role_code IN ('PRESIDENT','SECRETAIRE'))", account.getId());
        users.flush();
        var cu = fx.actAs(account);
        assertThat(members.applications(cu).bootstrap()).isTrue();

        Member approved = members.approve(self.getId(), new ApprovalRequest(plan.getId(), null), cu);
        assertThat(approved.getStatus()).isEqualTo(MemberStatus.ACTIF);
        assertThat(jdbc.queryForObject("SELECT details FROM audit_event WHERE action = 'ADHESION_APPROUVEE' AND entity_id = ?",
                String.class, String.valueOf(self.getId()))).contains("amorcage");
        // Une fois ce premier responsable en place, les autres demandes suivent la règle normale.
        assertThat(members.hasEstablishedApproverOtherThan(-1L)).isTrue();
    }
}
