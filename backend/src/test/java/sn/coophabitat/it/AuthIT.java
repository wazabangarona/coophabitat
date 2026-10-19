package sn.coophabitat.it;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.beans.factory.annotation.Autowired;
import sn.coophabitat.security.AuthController;
import sn.coophabitat.security.RateLimiter;
import sn.coophabitat.security.UserAccount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Authentification : messages génériques, verrouillage, rotation des jetons de rafraîchissement, contrôle d'accès. */
class AuthIT extends IntegrationTest {

    @Autowired RateLimiter limiter;

    @org.junit.jupiter.api.AfterEach
    void resetLimiter() {
        limiter.reset();
    }

    @Test
    void credentialEndpointsAreThrottledButSessionRenewalIsNot() throws Exception {
        limiter.reset();
        // Adresses différentes : on mesure le plafond par IP, pas le verrouillage par compte (10 essais / 15 min).
        for (int i = 0; i < 20; i++) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"inconnu" + i + "@test.invalid\",\"password\":\"mauvais-mot-de-passe\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"autre@test.invalid\",\"password\":\"mauvais-mot-de-passe\"}"))
                .andExpect(status().isTooManyRequests());
        // Le renouvellement de session (sans cookie ici) relève d'un autre plafond : il n'est pas bloqué.
        mvc.perform(post("/api/v1/auth/refresh").header(AuthController.CSRF_HEADER, "CoopHabitat"))
                .andExpect(status().isUnauthorized());
    }

    private MvcResult login(String email, String password, String ip) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .with(r -> { r.setRemoteAddr(ip); return r; })
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")).andReturn();
    }

    @Test
    void wrongPasswordAndUnknownAccountGiveTheSameAnswer() throws Exception {
        UserAccount u = fx.user(null, "MEMBRE");
        MvcResult wrong = login(u.getEmail(), "mauvais-mot-de-passe", "10.0.0.1");
        MvcResult unknown = login("inconnu-" + System.nanoTime() + "@test.invalid", "mauvais-mot-de-passe", "10.0.0.2");
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
        String a = wrong.getResponse().getContentAsString().replaceAll("\"timestamp\":\"[^\"]+\"", "");
        String b = unknown.getResponse().getContentAsString().replaceAll("\"timestamp\":\"[^\"]+\"", "");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void accountIsLockedAfterRepeatedFailures() throws Exception {
        UserAccount u = fx.user(null, "MEMBRE");
        for (int i = 0; i < 5; i++) {
            login(u.getEmail(), "mauvais-mot-de-passe", "10.1.0." + i);
        }
        // Même avec le bon mot de passe, le compte reste verrouillé pendant la durée prévue.
        assertThat(login(u.getEmail(), Fixtures.PASSWORD, "10.1.1.1").getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void loginRefreshRotationAndReuseDetection() throws Exception {
        UserAccount u = fx.user(null, "TRESORIER");
        MvcResult ok = login(u.getEmail(), Fixtures.PASSWORD, "10.2.0.1");
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        Cookie refresh = ok.getResponse().getCookie(AuthController.REFRESH_COOKIE);
        assertThat(refresh).isNotNull();
        assertThat(refresh.isHttpOnly()).isTrue();
        assertThat(ok.getResponse().getContentAsString()).doesNotContain(refresh.getValue());

        // Sans l'en-tête anti-CSRF, le rafraîchissement est refusé.
        assertThat(mvc.perform(post("/api/v1/auth/refresh").cookie(refresh)).andReturn().getResponse().getStatus())
                .isIn(401, 403);

        MvcResult rotated = mvc.perform(post("/api/v1/auth/refresh").cookie(refresh)
                .header(AuthController.CSRF_HEADER, "CoopHabitat")).andExpect(status().isOk()).andReturn();
        Cookie next = rotated.getResponse().getCookie(AuthController.REFRESH_COOKIE);
        assertThat(next.getValue()).isNotEqualTo(refresh.getValue());

        // Réutilisation de l'ancien jeton : refusée, et toute la famille est révoquée.
        assertThat(mvc.perform(post("/api/v1/auth/refresh").cookie(refresh).header(AuthController.CSRF_HEADER, "CoopHabitat"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(post("/api/v1/auth/refresh").cookie(next).header(AuthController.CSRF_HEADER, "CoopHabitat"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void protectedEndpointsRequireAuthenticationAndPermissions() throws Exception {
        mvc.perform(get("/api/v1/members")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/members").header("Authorization", "Bearer jeton.invalide.xyz")).andExpect(status().isUnauthorized());
        String memberToken = fx.token(fx.user(null, "MEMBRE"));
        mvc.perform(get("/api/v1/members").header("Authorization", bearer(memberToken))).andExpect(status().isForbidden());
        // La synthèse financière agrégée est publique pour les membres (transparence) ; le grand livre ne l'est pas.
        mvc.perform(get("/api/v1/finance/summary").header("Authorization", bearer(memberToken))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/finance/entries").header("Authorization", bearer(memberToken))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audit").header("Authorization", bearer(memberToken))).andExpect(status().isForbidden());
        // L'administrateur technique n'a aucun droit financier.
        String adminToken = fx.token(fx.user(null, "ADMINISTRATEUR_TECHNIQUE"));
        mvc.perform(get("/api/v1/finance/summary").header("Authorization", bearer(adminToken))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/users").header("Authorization", bearer(adminToken))).andExpect(status().isOk());
    }

    @Test
    void meEndpointReturnsPermissionsAndPublicEndpointsAreOpen() throws Exception {
        String t = fx.token(fx.user(null, "PRESIDENT"));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(t))).andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("PRESIDENT"));
        mvc.perform(get("/api/v1/public/info")).andExpect(status().isOk())
                .andExpect(jsonPath("$.escrowStatement").value("Fonds suivis comptablement — séquestre externe non vérifié ou non intégré"));
        mvc.perform(get("/api/v1/public/stats")).andExpect(status().isNotFound());
    }

    @Test
    void disablingAnAccountRevokesAccessImmediately() throws Exception {
        UserAccount target = fx.user(null, "SECRETAIRE");
        String targetToken = fx.token(target);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(targetToken))).andExpect(status().isOk());
        String adminToken = fx.token(fx.user(null, "ADMINISTRATEUR_TECHNIQUE"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/admin/users/" + target.getId() + "/enabled")
                        .header("Authorization", bearer(adminToken)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"reason\":\"Départ du bureau\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(targetToken))).andExpect(status().isUnauthorized());
    }
}
