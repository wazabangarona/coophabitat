package sn.coophabitat.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import sn.coophabitat.common.Json;
import sn.coophabitat.member.MemberDtos.CreateMemberRequest;
import sn.coophabitat.member.MemberService;
import sn.coophabitat.member.MemberType;

import java.time.LocalDate;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** L'administrateur technique définit et affecte les plans de cotisation, sans pouvoir valider d'adhésion. */
class AdminPlansIT extends IntegrationTest {

    @Autowired
    MemberService members;

    @Test
    void theAdministratorDefinesActivatesAndAssignsAPlanButDoesNotApproveMemberships() throws Exception {
        String admin = fx.token(fx.user(null, "ADMINISTRATEUR_TECHNIQUE"));
        String code = "ADMIN-" + Fixtures.next();
        String created = mvc.perform(post("/api/v1/contributions/plans").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"code":"%s","name":"Plan défini par l'administrateur","amount":15000,"periodicity":"MENSUELLE",
                                 "dueDayOfMonth":5,"validFrom":"%s","graceDays":5,"penaltyMethod":"AUCUNE","penaltyValue":0,
                                 "roundingMode":"HALF_UP","adoptedReference":"Résolution n° 1 de l'AG constitutive"}
                                """.formatted(code, LocalDate.now())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long planId = ((Number) Json.read(created, Map.class).get("id")).longValue();
        mvc.perform(post("/api/v1/contributions/plans/" + planId + "/activate").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIF"));

        var pending = members.create(new CreateMemberRequest("Demandeur" + Fixtures.next(), "Test", null, null, null,
                "Ville", LocalDate.now(), MemberType.ORDINAIRE, null, null, false), fx.actAs(fx.user(null, "SECRETAIRE")));
        long id = pending.member().id();
        mvc.perform(get("/api/v1/members/applications").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canDecide").value(false));
        mvc.perform(post("/api/v1/members/" + id + "/plan").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"planId\":" + planId + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.contributionPlanId").value(planId));
        mvc.perform(post("/api/v1/members/plan").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"planId\":" + planId + ",\"memberIds\":[" + id + "]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unchanged").value(1));

        // La validation des adhésions reste au bureau.
        mvc.perform(post("/api/v1/members/" + id + "/approve").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }
}
