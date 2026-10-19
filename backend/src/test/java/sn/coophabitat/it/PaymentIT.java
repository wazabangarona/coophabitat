package sn.coophabitat.it;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import sn.coophabitat.common.Json;
import sn.coophabitat.contribution.MemberObligation;
import sn.coophabitat.contribution.MemberObligationRepository;
import sn.coophabitat.contribution.ObligationService;
import sn.coophabitat.contribution.ObligationStatus;
import sn.coophabitat.contribution.PenaltyMethod;
import sn.coophabitat.member.Member;
import sn.coophabitat.payment.*;
import sn.coophabitat.payment.PaymentDtos.ManualPaymentRequest;
import sn.coophabitat.payment.provider.WebhookSignature;
import sn.coophabitat.security.UserAccount;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Paiements : idempotence, webhooks signés, échecs, simulation, encaissements manuels, remboursements. */
class PaymentIT extends IntegrationTest {

    @Autowired ObligationService obligations;
    @Autowired MemberObligationRepository obligationRepository;
    @Autowired PaymentIntentRepository intents;
    @Autowired PaymentTransactionRepository transactions;
    @Autowired PaymentService payments;

    Member member;
    UserAccount account;
    String token;
    MemberObligation first;

    @BeforeEach
    void setUp() {
        LocalDate start = LocalDate.now().minusMonths(2).withDayOfMonth(1);
        var plan = fx.activePlan(BigDecimal.valueOf(20_000), start, 5, PenaltyMethod.AUCUNE, BigDecimal.ZERO);
        member = fx.activeMember(plan.getId(), start);
        obligations.generateForMember(member.getId(), LocalDate.now());
        account = fx.memberAccount(member);
        token = fx.token(account);
        first = obligationRepository.findByMemberIdOrderByDueDateAscIdAsc(member.getId()).get(0);
    }

    private MvcResult createIntent(String key, long amount) throws Exception {
        return mvc.perform(post("/api/v1/payments/intents").header("Authorization", bearer(token))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + ",\"obligationId\":" + first.getId() + ",\"provider\":\"MOCK\"}"))
                .andReturn();
    }

    private PaymentIntent intentOf(MvcResult r) throws Exception {
        Map<?, ?> body = Json.read(r.getResponse().getContentAsString(), Map.class);
        return intents.findById(((Number) body.get("id")).longValue()).orElseThrow();
    }

    private String event(PaymentIntent i, boolean success, String eventId) {
        return Json.write(Map.of("id", eventId, "type", success ? "checkout.session.completed" : "checkout.session.payment_failed",
                "data", Map.of("id", i.getProviderSessionId(), "client_reference", i.getReference(),
                        "payment_status", success ? "succeeded" : "cancelled", "transaction_id", "T-" + eventId)));
    }

    private MvcResult webhook(String body, String signature) throws Exception {
        return mvc.perform(post("/api/v1/payments/webhooks/mock").contentType(MediaType.APPLICATION_JSON)
                .header("Wave-Signature", signature).content(body)).andReturn();
    }

    private String sign(String body) {
        return WebhookSignature.header(MOCK_SECRET, Instant.now().getEpochSecond(), body);
    }

    @Test
    void sameIdempotencyKeyReturnsTheSameIntent() throws Exception {
        String key = UUID.randomUUID().toString();
        PaymentIntent a = intentOf(createIntent(key, 20_000));
        PaymentIntent b = intentOf(createIntent(key, 20_000));
        assertThat(a.getId()).isEqualTo(b.getId());
        assertThat(a.getStatus()).isEqualTo(PaymentStatus.EN_ATTENTE);
        assertThat(a.isSimulated()).isTrue();
    }

    @Test
    void anIntentIsNotAPaymentAndInvalidSignatureIsRejected() throws Exception {
        PaymentIntent i = intentOf(createIntent(UUID.randomUUID().toString(), 20_000));
        assertThat(transactions.findByIntentId(i.getId())).isEmpty();
        String body = event(i, true, "evt-" + UUID.randomUUID());
        assertThat(webhook(body, "t=1,v1=00").getResponse().getStatus()).isEqualTo(401);
        assertThat(webhook(body, WebhookSignature.header("mauvais-secret", Instant.now().getEpochSecond(), body))
                .getResponse().getStatus()).isEqualTo(401);
        assertThat(transactions.findByIntentId(i.getId())).isEmpty();
        assertThat(obligationRepository.findById(first.getId()).orElseThrow().getAmountPaid()).isEqualByComparingTo("0");
    }

    @Test
    void duplicateWebhookCreatesASingleTransactionAndReceipt() throws Exception {
        PaymentIntent i = intentOf(createIntent(UUID.randomUUID().toString(), 20_000));
        String body = event(i, true, "evt-" + UUID.randomUUID());
        MvcResult r1 = webhook(body, sign(body));
        MvcResult r2 = webhook(body, sign(body));
        assertThat(r1.getResponse().getContentAsString()).contains("CONFIRME");
        assertThat(r2.getResponse().getContentAsString()).contains("DEJA_RECU");
        PaymentTransaction tx = transactions.findByIntentId(i.getId()).orElseThrow();
        assertThat(tx.isSimulated()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM receipt WHERE transaction_id = ?", Long.class, tx.getId())).isEqualTo(1);
        assertThat(obligationRepository.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(ObligationStatus.PAYEE);
        // Une notification différente (autre identifiant d'événement) ne double pas non plus le paiement.
        String other = event(i, true, "evt-" + UUID.randomUUID());
        webhook(other, sign(other));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transaction WHERE intent_id = ?", Long.class, i.getId())).isEqualTo(1);
        // Écriture simulée sur le compte de simulation, jamais sur la trésorerie réelle.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM accounting_line l JOIN accounting_entry e ON e.id = l.entry_id
                WHERE e.source_type = 'PAIEMENT' AND e.source_id = ? AND e.simulated AND l.account_code = '559900'""",
                Long.class, tx.getId())).isEqualTo(1);
    }

    @Test
    void failedPaymentCreditsNothing() throws Exception {
        PaymentIntent i = intentOf(createIntent(UUID.randomUUID().toString(), 20_000));
        String body = event(i, false, "evt-" + UUID.randomUUID());
        webhook(body, sign(body));
        assertThat(intents.findById(i.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.ECHOUE);
        assertThat(transactions.findByIntentId(i.getId())).isEmpty();
        assertThat(obligationRepository.findById(first.getId()).orElseThrow().getAmountPaid()).isEqualByComparingTo("0");
        // Un succès tardif sur une intention échouée ne doit pas non plus créditer silencieusement.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM receipt WHERE member_id = ?", Long.class, member.getId())).isZero();
    }

    @Test
    void anotherMemberCannotSeeOrSimulateThePayment() throws Exception {
        PaymentIntent i = intentOf(createIntent(UUID.randomUUID().toString(), 20_000));
        Member other = fx.activeMember(member.getContributionPlanId(), LocalDate.now().minusYears(1));
        String otherToken = fx.token(fx.memberAccount(other));
        mvc.perform(get("/api/v1/payments/intents/" + i.getId()).header("Authorization", bearer(otherToken)))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(403, 404));
        mvc.perform(get("/api/v1/payments/members/" + member.getId() + "/transactions").header("Authorization", bearer(otherToken)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/payments/simulation/" + i.getReference()).header("Authorization", bearer(otherToken))
                .contentType(MediaType.APPLICATION_JSON).content("{\"success\":true}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/members/" + member.getId() + "/summary").header("Authorization", bearer(otherToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void amountsAreValidated() throws Exception {
        assertThat(createIntent(UUID.randomUUID().toString(), 50).getResponse().getStatus()).isIn(400, 422);
        assertThat(createIntent(UUID.randomUUID().toString(), 0).getResponse().getStatus()).isEqualTo(400);
        mvc.perform(post("/api/v1/payments/intents").header("Authorization", bearer(token))
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100.5,\"provider\":\"MOCK\"}"))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(400, 422));
    }

    @Test
    void sendflashIsUnavailableAndNeverPretendsToSucceed() throws Exception {
        mvc.perform(post("/api/v1/payments/intents").header("Authorization", bearer(token))
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":20000,\"provider\":\"SENDFLASH\"}"))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(422, 503));
        mvc.perform(get("/api/v1/payments/providers").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'SENDFLASH')].available").value(false));
    }

    @Test
    void manualPaymentNeedsASecondPersonAndCanBeRefunded() {
        UserAccount treasurer = fx.user(null, "TRESORIER");
        UserAccount president = fx.user(null, "PRESIDENT");
        var t = fx.actAs(treasurer);
        PaymentIntent manual = payments.recordManual(new ManualPaymentRequest(member.getId(), BigDecimal.valueOf(20_000),
                first.getId(), "ESPECES", null, LocalDate.now(), "Remis en main propre"), UUID.randomUUID().toString(), t);
        assertThat(manual.getStatus()).isEqualTo(PaymentStatus.EN_VERIFICATION);
        assertThat(transactions.findByIntentId(manual.getId())).isEmpty();
        assertThatThrownBy(() -> payments.verifyManual(manual.getId(), true, "moi-même", t))
                .hasMessageContaining("autre");
        var p = fx.actAs(president);
        payments.verifyManual(manual.getId(), true, "Vu", p);
        PaymentTransaction tx = transactions.findByIntentId(manual.getId()).orElseThrow();
        assertThat(tx.isSimulated()).isFalse();
        assertThat(obligationRepository.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(ObligationStatus.PAYEE);

        fx.actAs(treasurer);
        payments.refund(tx.getId(), "Erreur de saisie constatée", t);
        assertThat(transactions.findById(tx.getId()).orElseThrow().getStatus()).isEqualTo(PaymentTransaction.Status.REMBOURSE);
        assertThat(obligationRepository.findById(first.getId()).orElseThrow().getAmountPaid()).isEqualByComparingTo("0");
        // L'écriture d'origine et sa contre-écriture coexistent : rien n'est effacé.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounting_entry WHERE source_type = 'PAIEMENT' AND source_id = ?",
                Long.class, tx.getId())).isEqualTo(2);
        assertThatThrownBy(() -> payments.refund(tx.getId(), "Deuxième remboursement", t)).isNotNull();
    }

    @Test
    void confirmIsIdempotent() throws Exception {
        PaymentIntent i = intentOf(createIntent(UUID.randomUUID().toString(), 20_000));
        PaymentTransaction a = payments.confirm(i.getId(), "X1", "SIMULATION", null);
        PaymentTransaction b = payments.confirm(i.getId(), "X1", "SIMULATION", null);
        assertThat(a.getId()).isEqualTo(b.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_allocation WHERE transaction_id = ?", Long.class, a.getId()))
                .isEqualTo(1);
    }
}
