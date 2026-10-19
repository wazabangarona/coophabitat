package sn.coophabitat.allocation;

import org.springframework.stereotype.Component;
import sn.coophabitat.config.ClockConfig;
import sn.coophabitat.contribution.MemberObligation;
import sn.coophabitat.contribution.MemberObligationRepository;
import sn.coophabitat.contribution.ObligationStatus;
import sn.coophabitat.member.Member;
import sn.coophabitat.member.MemberStatus;
import sn.coophabitat.member.MembershipStatusHistory;
import sn.coophabitat.member.MembershipStatusHistoryRepository;
import sn.coophabitat.payment.PaymentAllocationRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Évaluation de l'éligibilité à la date de référence, à partir des seules données persistées
 * (historique des statuts, échéances et imputations datées) : relancer l'évaluation donne le même résultat.
 */
@Component
public class EligibilityEvaluator {

    private final MembershipStatusHistoryRepository statusHistory;
    private final MemberObligationRepository obligations;
    private final PaymentAllocationRepository allocations;

    public EligibilityEvaluator(MembershipStatusHistoryRepository statusHistory, MemberObligationRepository obligations,
                                PaymentAllocationRepository allocations) {
        this.statusHistory = statusHistory;
        this.obligations = obligations;
        this.allocations = allocations;
    }

    public record Evaluation(boolean eligible, String reason, Map<String, Object> snapshot) {
    }

    public Evaluation evaluate(AllocationCampaign c, Member m) {
        LocalDate ref = c.getReferenceDate();
        Instant endOfRef = ref.plusDays(1).atStartOfDay(ClockConfig.DAKAR).toInstant();
        MemberStatus statusAtRef = statusAt(m.getId(), endOfRef);
        long seniority = m.getMembershipDate().isAfter(ref) ? -1 : ChronoUnit.MONTHS.between(m.getMembershipDate(), ref);
        BigDecimal arrears = BigDecimal.ZERO;
        for (MemberObligation o : obligations.findByMemberIdOrderByDueDateAscIdAsc(m.getId())) {
            if (o.getStatus() == ObligationStatus.ANNULEE || !o.getDueDate().isBefore(ref)) {
                continue;
            }
            BigDecimal paidAtRef = allocations.sumForObligationBefore(o.getId(), endOfRef);
            arrears = arrears.add(o.getAmountDue().subtract(paidAtRef).max(BigDecimal.ZERO));
        }
        List<String> types = Arrays.stream(c.getAllowedMemberTypes().split(",")).map(String::trim).toList();
        List<String> failures = new ArrayList<>();
        if (statusAtRef != MemberStatus.ACTIF) {
            failures.add("statut à la date de référence : " + (statusAtRef == null ? "inexistant" : statusAtRef));
        }
        if (seniority < c.getMinSeniorityMonths()) {
            failures.add("ancienneté " + Math.max(seniority, 0) + " mois < " + c.getMinSeniorityMonths() + " mois requis");
        }
        if (!types.contains(m.getMemberType().name())) {
            failures.add("type d'adhérent " + m.getMemberType() + " non admis");
        }
        if (c.isRequireUpToDate() && arrears.signum() > 0) {
            failures.add("arriérés de " + arrears + " FCFA à la date de référence (cotisations à jour exigées)");
        } else if (arrears.compareTo(c.getMaxArrearsAmount()) > 0) {
            failures.add("arriérés de " + arrears + " FCFA > plafond de " + c.getMaxArrearsAmount() + " FCFA");
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("date_reference", ref.toString());
        snapshot.put("statut", String.valueOf(statusAtRef));
        snapshot.put("anciennete_mois", seniority);
        snapshot.put("type", m.getMemberType().name());
        snapshot.put("arrieres", arrears);
        snapshot.put("regles", Map.of("anciennete_min", c.getMinSeniorityMonths(), "a_jour", c.isRequireUpToDate(),
                "arrieres_max", c.getMaxArrearsAmount(), "types", types));
        return new Evaluation(failures.isEmpty(), failures.isEmpty() ? "Tous les critères publiés sont remplis"
                : String.join(" ; ", failures), snapshot);
    }

    private MemberStatus statusAt(Long memberId, Instant instant) {
        MemberStatus status = null;
        for (MembershipStatusHistory h : statusHistory.findByMemberIdOrderByChangedAtAscIdAsc(memberId)) {
            if (h.getChangedAt().isBefore(instant)) {
                status = h.getToStatus();
            }
        }
        return status;
    }
}
