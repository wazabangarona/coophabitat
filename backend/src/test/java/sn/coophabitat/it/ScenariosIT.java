package sn.coophabitat.it;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import sn.coophabitat.common.Json;
import sn.coophabitat.contribution.MemberObligation;
import sn.coophabitat.contribution.MemberObligationRepository;
import sn.coophabitat.contribution.ObligationService;
import sn.coophabitat.contribution.ObligationStatus;
import sn.coophabitat.contribution.PenaltyMethod;
import sn.coophabitat.member.Member;
import sn.coophabitat.payment.*;
import sn.coophabitat.payment.provider.WebhookSignature;
import sn.coophabitat.security.UserAccount;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Scénarios obligatoires du cahier des charges non couverts ailleurs : création et suspension d'un membre,
 * paiement partiel, reprise après indisponibilité du fournisseur (adaptateur Wave réel face à un faux serveur
 * Wave local), double contrôle d'un webhook, exports PDF et Excel par l'API.
 */
class ScenariosIT extends IntegrationTest {

    static final String WAVE_KEY = "wave_sn_test_cle_factice";
    static final String WAVE_SECRET = "wave-webhook-secret-de-test";
    static final FakeWave WAVE = FakeWave.start();

    @DynamicPropertySource
    static void wave(DynamicPropertyRegistry r) {
        r.add("coophabitat.payment.wave.enabled", () -> "true");
        r.add("coophabitat.payment.wave.base-url", WAVE::url);
        r.add("coophabitat.payment.wave.api-key", () -> WAVE_KEY);
        r.add("coophabitat.payment.wave.webhook-secret", () -> WAVE_SECRET);
        r.add("coophabitat.payment.wave.success-url", () -> "https://coop.test.invalid/espace/paiements");
        r.add("coophabitat.payment.wave.error-url", () -> "https://coop.test.invalid/espace/paiements");
    }

    @AfterAll
    static void stopWave() {
        WAVE.server.stop(0);
    }

    @Autowired ObligationService obligations;
    @Autowired MemberObligationRepository obligationRepository;
    @Autowired PaymentIntentRepository intents;
    @Autowired PaymentTransactionRepository transactions;
    @Autowired PaymentService payments;

    Member member;
    String token;
    MemberObligation first;

    @BeforeEach
    void setUp() {
        WAVE.reset();
        LocalDate start = LocalDate.now().minusMonths(1).withDayOfMonth(1);
        var plan = fx.activePlan(BigDecimal.valueOf(20_000), start, 5, PenaltyMethod.AUCUNE, BigDecimal.ZERO);
        member = fx.activeMember(plan.getId(), start);
        obligations.generateForMember(member.getId(), LocalDate.now());
        UserAccount account = fx.memberAccount(member);
        token = fx.token(account);
        first = obligationRepository.findByMemberIdOrderByDueDateAscIdAsc(member.getId()).get(0);
    }

    private org.springframework.test.web.servlet.ResultActions intent(String key, long amount, String provider) throws Exception {
        return mvc.perform(post("/api/v1/payments/intents").header("Authorization", bearer(token))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":" + amount + ",\"obligationId\":" + first.getId() + ",\"provider\":\"" + provider + "\"}"));
    }

    private PaymentIntent latestIntent() {
        return intents.findAll().stream().filter(i -> i.getMemberId().equals(member.getId()))
                .max(java.util.Comparator.comparing(PaymentIntent::getId)).orElseThrow();
    }

    @Test
    void creationAndSuspensionOfAMemberAreTracedWithTheirReasons() throws Exception {
        var plan = fx.activePlan(BigDecimal.valueOf(15_000), LocalDate.now(), 5, PenaltyMethod.AUCUNE, BigDecimal.ZERO);
        String sec = fx.token(fx.user(null, "SECRETAIRE"));
        // Champ booléen omis : l'erreur nomme le champ en cause.
        mvc.perform(post("/api/v1/members").header("Authorization", bearer(sec)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Awa\",\"lastName\":\"Test\",\"membershipDate\":\"" + LocalDate.now()
                                + "\",\"memberType\":\"ORDINAIRE\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("createAccount"));
        String body = mvc.perform(post("/api/v1/members").header("Authorization", bearer(sec))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Awa\",\"lastName\":\"Test fictif\",\"city\":\"Thiès\","
                                + "\"membershipDate\":\"" + LocalDate.now() + "\",\"memberType\":\"ORDINAIRE\",\"createAccount\":false,"
                                + "\"contributionPlanId\":" + plan.getId() + "}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.member.status").value("EN_ATTENTE"))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) ((Map<?, ?>) Json.read(body, Map.class).get("member")).get("id")).longValue();

        mvc.perform(post("/api/v1/members/" + id + "/status").header("Authorization", bearer(sec))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIF\",\"reason\":\"Dossier complet\"}"))
                .andExpect(status().isOk());
        // Une suspension sans motif est refusée ; avec motif, elle est enregistrée.
        mvc.perform(post("/api/v1/members/" + id + "/status").header("Authorization", bearer(sec))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDU\",\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/members/" + id + "/status").header("Authorization", bearer(sec))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDU\",\"reason\":\"Décision du bureau du jour (test)\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUSPENDU"));
        // Transition interdite : d'un statut suspendu on ne revient pas « en attente ».
        mvc.perform(post("/api/v1/members/" + id + "/status").header("Authorization", bearer(sec))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"EN_ATTENTE\",\"reason\":\"Retour arrière\"}"))
                .andExpect(status().is4xxClientError());

        mvc.perform(get("/api/v1/members/" + id + "/history").header("Authorization", bearer(sec)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.toStatus == 'SUSPENDU')].reason").value("Décision du bureau du jour (test)"));
        // Un membre ne peut pas changer un statut.
        mvc.perform(post("/api/v1/members/" + id + "/status").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIF\",\"reason\":\"Je me réactive\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void partialPaymentLeavesTheRemainderDue() throws Exception {
        intent("partiel-" + UUID.randomUUID(), 8_000, "MOCK").andExpect(status().is2xxSuccessful());
        PaymentIntent i = latestIntent();
        mvc.perform(post("/api/v1/payments/simulation/" + i.getReference()).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"success\":true}")).andExpect(status().isOk());

        MemberObligation o = obligationRepository.findById(first.getId()).orElseThrow();
        assertThat(o.getStatus()).isEqualTo(ObligationStatus.PARTIELLE);
        assertThat(o.outstanding()).isEqualByComparingTo("12000");
        // Le solde restant reste payable, mais pas au-delà du dû et de l'avance autorisée.
        intent("trop-" + UUID.randomUUID(), 10_000_000, "MOCK").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void providerOutageNeverCountsMoneyAndTheSameRequestResumesAfterwards() throws Exception {
        String key = "wave-" + UUID.randomUUID();

        WAVE.down.set(true);
        intent(key, 20_000, "WAVE").andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("FOURNISSEUR_ERREUR"));
        PaymentIntent pending = latestIntent();
        assertThat(pending.getStatus()).isEqualTo(PaymentStatus.INITIE);
        assertThat(transactions.findByIntentId(pending.getId())).isEmpty();

        // Wave revient : la même demande (même clé d'idempotence) reprend sans créer de doublon.
        WAVE.down.set(false);
        intent(key, 20_000, "WAVE").andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.status").value("EN_ATTENTE"))
                .andExpect(jsonPath("$.launchUrl").value(org.hamcrest.Matchers.startsWith("https://pay.wave.com/c/cos-")));
        assertThat(latestIntent().getId()).isEqualTo(pending.getId());
        assertThat(WAVE.lastAuthorization.get()).isEqualTo("Bearer " + WAVE_KEY);
        assertThat(WAVE.lastBody.get()).contains("\"amount\":\"20000\"").contains("\"currency\":\"XOF\"")
                .contains("\"client_reference\":\"" + pending.getReference() + "\"");

        // Vérification pendant une nouvelle coupure : rien n'est comptabilisé, la cause est conservée.
        WAVE.down.set(true);
        payments.checkWithProvider(pending.getId());
        PaymentIntent afterOutage = intents.findById(pending.getId()).orElseThrow();
        assertThat(afterOutage.getStatus()).isEqualTo(PaymentStatus.EN_ATTENTE);
        assertThat(afterOutage.getFailureReason()).contains("Vérification impossible", "HTTP 503");
        assertThat(transactions.findByIntentId(pending.getId())).isEmpty();

        // Le webhook a été perdu ; la réconciliation interroge Wave et confirme une seule fois.
        WAVE.down.set(false);
        WAVE.paymentStatus.set("succeeded");
        payments.checkWithProvider(pending.getId());
        payments.checkWithProvider(pending.getId());
        assertThat(intents.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.CONFIRME);
        assertThat(transactions.findByIntentId(pending.getId())).isPresent()
                .get().extracting(PaymentTransaction::getAmount).satisfies(a -> assertThat(a).isEqualByComparingTo("20000"));
        assertThat(obligationRepository.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(ObligationStatus.PAYEE);
    }

    @Test
    void signedWaveSuccessIsNotCountedUntilWaveConfirmsIt() throws Exception {
        intent("wave-" + UUID.randomUUID(), 20_000, "WAVE").andExpect(status().is2xxSuccessful());
        PaymentIntent i = latestIntent();
        String body = Json.write(Map.of("id", "evt-" + UUID.randomUUID(), "type", "checkout.session.completed",
                "data", Map.of("id", i.getProviderSessionId(), "client_reference", i.getReference(),
                        "payment_status", "succeeded", "transaction_id", "TCN-TEST")));
        String signature = WebhookSignature.header(WAVE_SECRET, Instant.now().getEpochSecond(), body);

        // Signature valide, mais Wave indique encore « processing » : mise en vérification, rien n'est compté.
        WAVE.paymentStatus.set("processing");
        mvc.perform(post("/api/v1/payments/webhooks/wave").contentType(MediaType.APPLICATION_JSON)
                .header("Wave-Signature", signature).content(body)).andExpect(status().isOk());
        assertThat(intents.findById(i.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.EN_VERIFICATION);
        assertThat(transactions.findByIntentId(i.getId())).isEmpty();

        // Signature falsifiée : refus.
        mvc.perform(post("/api/v1/payments/webhooks/wave").contentType(MediaType.APPLICATION_JSON)
                .header("Wave-Signature", "t=" + Instant.now().getEpochSecond() + ",v1=00").content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void receiptAndReportsAreExportedAsRealPdfAndExcelFiles() throws Exception {
        intent("pdf-" + UUID.randomUUID(), 20_000, "MOCK").andExpect(status().is2xxSuccessful());
        mvc.perform(post("/api/v1/payments/simulation/" + latestIntent().getReference()).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"success\":true}")).andExpect(status().isOk());

        String receipts = mvc.perform(get("/api/v1/members/" + member.getId() + "/receipts").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long receiptId = ((Number) ((Map<?, ?>) Json.read(receipts, List.class).get(0)).get("id")).longValue();
        byte[] pdf = mvc.perform(get("/api/v1/receipts/" + receiptId + "/pdf").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        String treasurer = fx.token(fx.user(null, "TRESORIER"));
        LocalDate from = LocalDate.now().withDayOfYear(1);
        String report = mvc.perform(post("/api/v1/finance/reports").header("Authorization", bearer(treasurer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"PERIODE\",\"periodStart\":\"" + from + "\",\"periodEnd\":\"" + LocalDate.now() + "\"}"))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
        long documentId = ((Number) Json.read(report, Map.class).get("documentId")).longValue();
        byte[] reportPdf = mvc.perform(get("/api/v1/documents/" + documentId + "/content").header("Authorization", bearer(treasurer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(reportPdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        byte[] xlsx = mvc.perform(get("/api/v1/finance/export").param("from", from.toString())
                        .param("to", LocalDate.now().toString()).header("Authorization", bearer(treasurer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(wb.getNumberOfSheets()).isGreaterThan(0);
        }
        // Un membre n'accède ni au reçu d'un autre, ni aux exports comptables.
        var otherPlan = fx.activePlan(BigDecimal.valueOf(5_000), LocalDate.now(), 5, PenaltyMethod.AUCUNE, BigDecimal.ZERO);
        String other = fx.token(fx.memberAccount(fx.activeMember(otherPlan.getId(), LocalDate.now())));
        // 404 plutôt que 403 : l'existence du reçu d'autrui n'est pas révélée.
        mvc.perform(get("/api/v1/receipts/" + receiptId + "/pdf").header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/finance/export").param("from", from.toString()).param("to", LocalDate.now().toString())
                .header("Authorization", bearer(other))).andExpect(status().isForbidden());
    }

    /** Faux serveur Wave local : reproduit les réponses documentées de l'API Checkout, ou une panne. */
    static final class FakeWave {
        final HttpServer server;
        final AtomicBoolean down = new AtomicBoolean();
        final AtomicReference<String> paymentStatus = new AtomicReference<>("processing");
        final AtomicReference<String> lastAuthorization = new AtomicReference<>();
        final AtomicReference<String> lastBody = new AtomicReference<>();
        final java.util.concurrent.atomic.AtomicInteger sessions = new java.util.concurrent.atomic.AtomicInteger();

        private FakeWave(HttpServer server) {
            this.server = server;
        }

        static FakeWave start() {
            try {
                HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                FakeWave w = new FakeWave(s);
                s.createContext("/v1/checkout/sessions", w::handle);
                s.start();
                return w;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void reset() {
            down.set(false);
            paymentStatus.set("processing");
        }

        private void handle(HttpExchange ex) throws IOException {
            if (down.get()) {
                reply(ex, 503, "{\"code\":\"service-unavailable\"}");
                return;
            }
            lastAuthorization.set(ex.getRequestHeaders().getFirst("Authorization"));
            if ("POST".equals(ex.getRequestMethod())) {
                lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String id = "cos-" + sessions.incrementAndGet();
                reply(ex, 200, "{\"id\":\"" + id + "\",\"wave_launch_url\":\"https://pay.wave.com/c/" + id + "\","
                        + "\"checkout_status\":\"open\",\"payment_status\":\"processing\","
                        + "\"when_expires\":\"" + Instant.now().plusSeconds(1800) + "\"}");
            } else {
                String paid = paymentStatus.get();
                reply(ex, 200, "{\"checkout_status\":\"" + ("succeeded".equals(paid) ? "complete" : "open")
                        + "\",\"payment_status\":\"" + paid + "\",\"transaction_id\":\"TCN-RECONCILIE\"}");
            }
        }

        private static void reply(HttpExchange ex, int code, String json) throws IOException {
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(code, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        }
    }
}
