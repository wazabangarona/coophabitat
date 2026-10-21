package sn.coophabitat.it;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import sn.coophabitat.contribution.PenaltyMethod;
import sn.coophabitat.member.Member;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Parcours HTTP de bout en bout sur les écrans principaux : chaque rôle obtient ses tableaux de bord et listes,
 * aucune route ne renvoie d'erreur interne, et un membre n'accède qu'à son propre espace.
 */
class ApiSweepIT extends IntegrationTest {

    private static final List<String> BUREAU_GETS = List.of("/api/v1/members", "/api/v1/members?q=nom&status=ACTIF",
            "/api/v1/contributions/plans", "/api/v1/payments/intents", "/api/v1/property/lots?q=lot", "/api/v1/property/projects",
            "/api/v1/allocations", "/api/v1/assemblies", "/api/v1/finance/summary", "/api/v1/finance/expenses",
            "/api/v1/finance/budgets", "/api/v1/finance/accounts", "/api/v1/finance/entries?from=2026-01-01&to=2030-12-31",
            "/api/v1/finance/trial-balance?from=2026-01-01&to=2030-12-31", "/api/v1/finance/bank/reconciliation",
            "/api/v1/finance/reports", "/api/v1/dashboard/bureau", "/api/v1/documents", "/api/v1/notifications/queue",
            "/api/v1/audit", "/api/v1/audit/verify", "/api/v1/admin/settings");

    @Test
    void bureauRolesGetTheirScreensWithoutServerErrors() throws Exception {
        for (String role : List.of("PRESIDENT", "TRESORIER", "SECRETAIRE", "AUDITEUR", "AGENT_DE_GESTION")) {
            String t = fx.token(fx.user(null, role));
            for (String path : BUREAU_GETS) {
                int code = mvc.perform(get(path).header("Authorization", bearer(t))).andReturn().getResponse().getStatus();
                assertThat(code).as(role + " " + path).isIn(200, 403);
            }
        }
        String treasurer = fx.token(fx.user(null, "TRESORIER"));
        mvc.perform(get("/api/v1/dashboard/bureau").header("Authorization", bearer(treasurer))).andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[?(@.key == 'tresorerie')]").exists());
        mvc.perform(get("/api/v1/finance/export?from=2026-01-01&to=2030-12-31").header("Authorization", bearer(treasurer)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/payments/export").header("Authorization", bearer(treasurer))).andExpect(status().isOk());
        String secretary = fx.token(fx.user(null, "SECRETAIRE"));
        mvc.perform(get("/api/v1/members/export").header("Authorization", bearer(secretary))).andExpect(status().isOk());
    }

    @Test
    void budgetAndManualEntriesThroughTheApi() throws Exception {
        String treasurer = fx.token(fx.user(null, "TRESORIER"));
        int year = 2100 + Fixtures.next() % 90;
        String created = mvc.perform(post("/api/v1/finance/budgets").header("Authorization", bearer(treasurer))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"fiscalYear": %d, "label": "Budget de test", "lines": [
                                  {"category": "COTISATIONS", "direction": "RECETTE", "plannedAmount": 1000000},
                                  {"category": "FOURNITURES", "direction": "DEPENSE", "plannedAmount": 50000}]}""".formatted(year)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) sn.coophabitat.common.Json.read(created, java.util.Map.class).get("id")).longValue();
        mvc.perform(post("/api/v1/finance/budgets/" + id + "/adopt").header("Authorization", bearer(treasurer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"adoptedReference\":\"Résolution AG n°3\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ADOPTE"));
        mvc.perform(put("/api/v1/finance/budgets/" + id).header("Authorization", bearer(treasurer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"fiscalYear\":%d,\"label\":\"x\",\"lines\":[]}".formatted(year)))
                .andExpect(status().isUnprocessableContent());

        String entry = mvc.perform(post("/api/v1/finance/entries").header("Authorization", bearer(treasurer))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"entryDate": "%s", "label": "Frais bancaires", "justification": "Relevé bancaire du mois",
                                 "lines": [{"accountCode": "631100", "debit": 2500, "credit": 0},
                                           {"accountCode": "521100", "debit": 0, "credit": 2500}]}""".formatted(LocalDate.now())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long entryId = ((Number) sn.coophabitat.common.Json.read(entry, java.util.Map.class).get("id")).longValue();
        mvc.perform(post("/api/v1/finance/entries/" + entryId + "/reverse").header("Authorization", bearer(treasurer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Frais remboursés par la banque\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reversalOfId").value(entryId));
        mvc.perform(post("/api/v1/finance/entries").header("Authorization", bearer(treasurer))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"entryDate": "%s", "label": "Tentative", "justification": "Essai sur le compte simulé",
                         "lines": [{"accountCode": "559900", "debit": 100, "credit": 0},
                                   {"accountCode": "467100", "debit": 0, "credit": 100}]}""".formatted(LocalDate.now())))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void adminManagesAccountsWithoutSelfEscalation() throws Exception {
        var admin = fx.user(null, "ADMINISTRATEUR_TECHNIQUE");
        String t = fx.token(admin);
        mvc.perform(get("/api/v1/admin/roles").header("Authorization", bearer(t))).andExpect(status().isOk());
        String body = mvc.perform(post("/api/v1/admin/users").header("Authorization", bearer(t)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nouveau-" + System.nanoTime() + "@test.invalid\",\"displayName\":\"Nouveau trésorier\","
                                + "\"roles\":[\"TRESORIER\"]}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.user.activated").value(false))
                // Sans serveur SMTP, le lien d'activation est rendu à l'administrateur pour qu'il le transmette.
                .andExpect(jsonPath("$.access.emailSent").value(false))
                .andExpect(jsonPath("$.access.link").value(org.hamcrest.Matchers.containsString("/activation?jeton=")))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) ((java.util.Map<?, ?>) sn.coophabitat.common.Json.read(body, java.util.Map.class).get("user")).get("id")).longValue();
        mvc.perform(put("/api/v1/admin/users/" + admin.getId() + "/roles").header("Authorization", bearer(t))
                .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[\"TRESORIER\"]}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/users/" + id + "/roles").header("Authorization", bearer(t))
                .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[\"PRESIDENT\",\"TRESORIER\"]}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(put("/api/v1/admin/users/" + id + "/roles").header("Authorization", bearer(t))
                .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[\"SECRETAIRE\"]}")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/users/" + id + "/invitation").header("Authorization", bearer(t))).andExpect(status().isOk())
                .andExpect(jsonPath("$.link").value(org.hamcrest.Matchers.containsString("/activation?jeton=")));
        // Un compte non activé ne se réinitialise pas : on renvoie l'invitation.
        mvc.perform(post("/api/v1/admin/users/" + id + "/password-reset").header("Authorization", bearer(t)))
                .andExpect(status().isUnprocessableContent());
        var member = fx.user(null, "MEMBRE");
        mvc.perform(post("/api/v1/admin/users/" + member.getId() + "/password-reset").header("Authorization", bearer(t)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.emailSent").value(false))
                .andExpect(jsonPath("$.link").value(org.hamcrest.Matchers.containsString("/reinitialisation?jeton=")));
        mvc.perform(post("/api/v1/admin/users/" + admin.getId() + "/password-reset").header("Authorization", bearer(t)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/users/" + id + "/unlock").header("Authorization", bearer(t))).andExpect(status().isNoContent());
        mvc.perform(put("/api/v1/admin/settings").header("Authorization", bearer(t)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Coopérative de test\",\"city\":\"Thiès\",\"publishPublicStats\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.escrowIntegrated").value(false));
        mvc.perform(get("/api/v1/public/stats")).andExpect(status().isOk());
        mvc.perform(put("/api/v1/admin/settings").header("Authorization", bearer(t)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Coopérative de test\",\"publishPublicStats\":false}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/retention-policies").header("Authorization", bearer(t))).andExpect(status().isOk());
    }

    @Test
    void memberSelfServiceAndDataRequests() throws Exception {
        var plan = fx.activePlan(BigDecimal.valueOf(1_000), LocalDate.now().withDayOfMonth(1), 0, PenaltyMethod.AUCUNE, BigDecimal.ZERO);
        Member m = fx.activeMember(plan.getId(), LocalDate.now().minusMonths(2));
        String t = fx.token(fx.memberAccount(m));
        for (String path : List.of("/api/v1/me/profile", "/api/v1/me/summary", "/api/v1/me/obligations", "/api/v1/me/data-requests",
                "/api/v1/allocations/me", "/api/v1/notifications/inbox", "/api/v1/notifications/inbox/unread-count",
                "/api/v1/members/" + m.getId() + "/receipts", "/api/v1/payments/members/" + m.getId() + "/transactions",
                "/api/v1/property/lots", "/api/v1/assemblies", "/api/v1/finance/reports", "/api/v1/finance/summary", "/api/v1/documents")) {
            mvc.perform(get(path).header("Authorization", bearer(t))).andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/me/profile").header("Authorization", bearer(t))).andExpect(jsonPath("$.notes").doesNotExist());
        mvc.perform(get("/api/v1/me/export").header("Authorization", bearer(t))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/me/data-requests").header("Authorization", bearer(t)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"ACCES\",\"details\":\"Je souhaite obtenir la copie de mes données.\"}"))
                .andExpect(status().isCreated());
        String handler = fx.token(fx.user(null, "SECRETAIRE"));
        String list = mvc.perform(get("/api/v1/admin/data-requests").header("Authorization", bearer(handler)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list).contains(m.getMemberNumber());
        long reqId = ((Number) ((java.util.Map<?, ?>) sn.coophabitat.common.Json.read(list, List.class).get(0)).get("id")).longValue();
        mvc.perform(post("/api/v1/admin/data-requests/" + reqId + "/decision").header("Authorization", bearer(handler))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"TRAITEE\",\"response\":\"Export transmis au membre.\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/members").header("Authorization", bearer(t))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/dashboard/bureau").header("Authorization", bearer(t))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/finance/expenses").header("Authorization", bearer(t))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/finance/entries").header("Authorization", bearer(t))).andExpect(status().isForbidden());
    }
}
