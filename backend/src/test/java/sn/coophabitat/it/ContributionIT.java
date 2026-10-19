package sn.coophabitat.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import sn.coophabitat.contribution.*;
import sn.coophabitat.contribution.ContributionDtos.AdjustmentRequest;
import sn.coophabitat.member.Member;
import sn.coophabitat.notification.ReminderService;
import sn.coophabitat.payment.PaymentDtos.ManualPaymentRequest;
import sn.coophabitat.payment.PaymentService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Échéancier, pénalités, rappels J-3, imputation des paiements et avances. */
class ContributionIT extends IntegrationTest {

    @Autowired ObligationService obligations;
    @Autowired MemberObligationRepository repository;
    @Autowired ReminderService reminders;
    @Autowired PaymentService payments;
    @Autowired PlanService plans;

    @Test
    void generationIsIdempotent() {
        LocalDate start = LocalDate.now().minusMonths(3).withDayOfMonth(1);
        var plan = fx.activePlan(BigDecimal.valueOf(15_000), start, 0, PenaltyMethod.AUCUNE, BigDecimal.ZERO);
        Member m = fx.activeMember(plan.getId(), start);
        int first = obligations.generateForMember(m.getId(), LocalDate.now());
        int second = obligations.generateForMember(m.getId(), LocalDate.now());
        assertThat(first).isGreaterThanOrEqualTo(3);
        assertThat(second).isZero();
        assertThat(repository.findByMemberIdOrderByDueDateAscIdAsc(m.getId()))
                .allMatch(o -> o.getAmountDue().compareTo(BigDecimal.valueOf(15_000)) == 0);
    }

    @Test
    void activePlanIsFrozen() {
        var plan = fx.activePlan(BigDecimal.valueOf(15_000), LocalDate.now().withDayOfMonth(1), 0, PenaltyMethod.AUCUNE, BigDecimal.ZERO);
        assertThatThrownBy(() -> plans.update(plan.getId(), new ContributionDtos.PlanRequest(plan.getCode(), "Modifié", null,
                BigDecimal.valueOf(1), Periodicity.MENSUELLE, 5, plan.getValidFrom(), null, 0, PenaltyMethod.AUCUNE,
                BigDecimal.ZERO, null, java.math.RoundingMode.HALF_UP, "x"))).isNotNull();
    }

    @Test
    void penaltiesAreCrystallisedOnceAndPaidAfterTheContribution() {
        LocalDate start = LocalDate.now().minusMonths(4).withDayOfMonth(1);
        var plan = fx.activePlan(BigDecimal.valueOf(10_000), start, 5, PenaltyMethod.FORFAIT, BigDecimal.valueOf(1_000));
        Member m = fx.activeMember(plan.getId(), start);
        obligations.generateForMember(m.getId(), LocalDate.now());
        obligations.assessPenalties(LocalDate.now());
        obligations.assessPenalties(LocalDate.now());
        List<MemberObligation> all = repository.findByMemberIdOrderByDueDateAscIdAsc(m.getId());
        long penalties = all.stream().filter(o -> o.getKind() == ObligationKind.PENALITE).count();
        long overdue = all.stream().filter(o -> o.getKind() == ObligationKind.COTISATION
                && o.getDueDate().plusDays(6).isBefore(LocalDate.now().plusDays(1))).count();
        assertThat(penalties).isEqualTo(overdue).isPositive();

        // Paiement exact de la première cotisation : la cotisation passe avant la pénalité.
        var t = fx.actAs(fx.user(null, "TRESORIER"));
        var intent = payments.recordManual(new ManualPaymentRequest(m.getId(), BigDecimal.valueOf(10_000), null, "ESPECES",
                null, LocalDate.now(), null), UUID.randomUUID().toString(), t);
        payments.verifyManual(intent.getId(), true, null, fx.actAs(fx.user(null, "PRESIDENT")));
        MemberObligation oldest = repository.findByMemberIdOrderByDueDateAscIdAsc(m.getId()).stream()
                .filter(o -> o.getKind() == ObligationKind.COTISATION).findFirst().orElseThrow();
        assertThat(oldest.getStatus()).isEqualTo(ObligationStatus.PAYEE);
        MemberObligation itsPenalty = repository.findByParentIdOrderByPenaltySequenceAsc(oldest.getId()).get(0);
        assertThat(itsPenalty.getAmountPaid()).isEqualByComparingTo("0");
    }

    @Test
    void penaltyRemissionRequiresAReason() {
        LocalDate start = LocalDate.now().minusMonths(3).withDayOfMonth(1);
        var plan = fx.activePlan(BigDecimal.valueOf(10_000), start, 0, PenaltyMethod.FORFAIT, BigDecimal.valueOf(500));
        Member m = fx.activeMember(plan.getId(), start);
        obligations.generateForMember(m.getId(), LocalDate.now());
        obligations.assessPenalties(LocalDate.now());
        MemberObligation penalty = repository.findByMemberIdOrderByDueDateAscIdAsc(m.getId()).stream()
                .filter(o -> o.getKind() == ObligationKind.PENALITE).findFirst().orElseThrow();
        var t = fx.actAs(fx.user(null, "TRESORIER"));
        var adjusted = obligations.adjust(penalty.getId(), new AdjustmentRequest(ObligationAdjustment.Type.REMISE_PENALITE, BigDecimal.ZERO,
                "Retard dû à une panne du réseau de paiement", null), t);
        assertThat(adjusted.outstanding()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM obligation_adjustment WHERE obligation_id = ?", Long.class,
                penalty.getId())).isEqualTo(1);
    }

    @Test
    void reminderIsSentThreeDaysBeforeDueDateOnlyOnce() {
        LocalDate today = LocalDate.now();
        LocalDate due = today.plusDays(3);
        var plan = fx.activePlan(BigDecimal.valueOf(5_000), due.withDayOfMonth(1), 0, PenaltyMethod.AUCUNE, BigDecimal.ZERO);
        Member m = fx.activeMember(plan.getId(), due.withDayOfMonth(1).minusMonths(1));
        fx.memberAccount(m);
        jdbc.update("UPDATE contribution_plan SET due_day_of_month = ? WHERE id = ?", due.getDayOfMonth(), plan.getId());
        obligations.generateForMember(m.getId(), today);
        MemberObligation o = repository.findByMemberIdOrderByDueDateAscIdAsc(m.getId()).stream()
                .filter(x -> x.getDueDate().equals(due)).findFirst().orElseThrow();
        reminders.run(today);
        reminders.run(today);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE dedup_key LIKE ?", Long.class,
                "RAPPEL_J3:" + o.getId() + "%")).isEqualTo(1);
        // À J-4 (trop tôt) ou à J-2 (déjà rappelée), aucun nouveau rappel pour cette échéance.
        reminders.run(today.minusDays(1));
        reminders.run(today.plusDays(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE dedup_key LIKE ?", Long.class,
                "RAPPEL_J3:" + o.getId() + "%")).isEqualTo(1);
    }

    @Test
    void aMissedReminderRunIsCaughtUpBeforeTheDueDate() {
        LocalDate today = LocalDate.now();
        LocalDate due = today.plusDays(2);
        var plan = fx.activePlan(BigDecimal.valueOf(5_000), due.withDayOfMonth(1), 0, PenaltyMethod.AUCUNE, BigDecimal.ZERO);
        Member m = fx.activeMember(plan.getId(), due.withDayOfMonth(1).minusMonths(1));
        fx.memberAccount(m);
        jdbc.update("UPDATE contribution_plan SET due_day_of_month = ? WHERE id = ?", due.getDayOfMonth(), plan.getId());
        obligations.generateForMember(m.getId(), today);
        MemberObligation o = repository.findByMemberIdOrderByDueDateAscIdAsc(m.getId()).stream()
                .filter(x -> x.getDueDate().equals(due)).findFirst().orElseThrow();
        // L'exécution de J-3 n'a pas eu lieu : celle de J-2 envoie le rappel, une seule fois.
        reminders.run(today);
        reminders.run(today.plusDays(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE dedup_key LIKE ?", Long.class,
                "RAPPEL_J3:" + o.getId() + "%")).isEqualTo(1);
        // Le jour de l'échéance, il n'y a plus de rappel « avant échéance ».
        jdbc.update("DELETE FROM notification WHERE dedup_key LIKE ?", "RAPPEL_J3:" + o.getId() + "%");
        reminders.run(due);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE dedup_key LIKE ?", Long.class,
                "RAPPEL_J3:" + o.getId() + "%")).isZero();
    }

    @Test
    void advancePaymentIsKeptAsCreditAndAppliedToNextObligations() {
        LocalDate start = LocalDate.now().withDayOfMonth(1);
        var plan = fx.activePlan(BigDecimal.valueOf(10_000), start, 0, PenaltyMethod.AUCUNE, BigDecimal.ZERO);
        Member m = fx.activeMember(plan.getId(), start);
        var t = fx.actAs(fx.user(null, "TRESORIER"));
        var intent = payments.recordManual(new ManualPaymentRequest(m.getId(), BigDecimal.valueOf(30_000), null, "VIREMENT",
                "VIR-1", LocalDate.now(), null), UUID.randomUUID().toString(), t);
        payments.verifyManual(intent.getId(), true, null, fx.actAs(fx.user(null, "PRESIDENT")));
        obligations.generateForMember(m.getId(), LocalDate.now());
        var summary = obligations.summary(m.getId());
        assertThat(summary.totalPaid().add(summary.credit())).isEqualByComparingTo("30000");
    }

    @Test
    void memberCanUpdateOnlyTheirOwnContact() throws Exception {
        var plan = fx.activePlan(BigDecimal.valueOf(5_000), LocalDate.now().withDayOfMonth(1), 0, PenaltyMethod.AUCUNE, BigDecimal.ZERO);
        Member m = fx.activeMember(plan.getId(), LocalDate.now().minusMonths(1));
        String token = fx.token(fx.memberAccount(m));
        mvc.perform(put("/api/v1/me/contact").header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nouvelle-adresse-" + m.getId() + "@test.invalid\",\"phone\":\"771234567\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT phone FROM member WHERE id = ?", String.class, m.getId())).isEqualTo("+221771234567");
    }
}
