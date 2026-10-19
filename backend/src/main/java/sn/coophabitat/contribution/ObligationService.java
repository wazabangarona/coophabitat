package sn.coophabitat.contribution;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import sn.coophabitat.audit.AuditCategory;
import sn.coophabitat.audit.AuditService;
import sn.coophabitat.common.BusinessException;
import sn.coophabitat.common.Money;
import sn.coophabitat.config.AppProperties;
import sn.coophabitat.config.ClockConfig;
import sn.coophabitat.contribution.ContributionDtos.MemberFinancialSummary;
import sn.coophabitat.contribution.ContributionDtos.ObligationResponse;
import sn.coophabitat.member.Member;
import sn.coophabitat.member.MemberRepository;
import sn.coophabitat.member.MemberStatus;
import sn.coophabitat.payment.PaymentAllocationRepository;
import sn.coophabitat.payment.PaymentAllocator;
import sn.coophabitat.payment.PaymentTransactionRepository;
import sn.coophabitat.security.CurrentUser;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Génération des échéances, application des pénalités, ajustements motivés et synthèse financière par membre. */
@Service
public class ObligationService {

    /** Horizon de génération : les échéances des 35 prochains jours existent (rappels J-3, affichage). */
    static final int HORIZON_DAYS = 35;

    private final MemberObligationRepository obligations;
    private final ContributionPlanRepository plans;
    private final MemberRepository members;
    private final ObligationAdjustmentRepository adjustments;
    private final PaymentAllocationRepository allocations;
    private final PaymentTransactionRepository transactions;
    private final PaymentAllocator allocator;
    private final AuditService audit;
    private final AppProperties props;
    private final Clock clock;
    private final TransactionTemplate tx;

    public ObligationService(MemberObligationRepository obligations, ContributionPlanRepository plans,
                             MemberRepository members, ObligationAdjustmentRepository adjustments,
                             PaymentAllocationRepository allocations, PaymentTransactionRepository transactions,
                             PaymentAllocator allocator, AuditService audit, AppProperties props, Clock clock,
                             PlatformTransactionManager tm) {
        this.obligations = obligations;
        this.plans = plans;
        this.members = members;
        this.adjustments = adjustments;
        this.allocations = allocations;
        this.transactions = transactions;
        this.allocator = allocator;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
        this.tx = new TransactionTemplate(tm);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    // ------------------------------------------------------------------ génération

    /** Génère les échéances manquantes de tous les membres concernés (idempotent : contrainte d'unicité par période). */
    public ContributionDtos.GenerationResult generateAll(LocalDate asOf) {
        List<MemberStatus> statuses = props.contribution().generateForStatuses().stream().map(MemberStatus::valueOf).toList();
        int created = 0;
        int processed = 0;
        for (Member m : members.findByStatusIn(statuses)) {
            if (m.getContributionPlanId() == null) {
                continue;
            }
            Integer n = tx.execute(s -> generateForMember(m.getId(), asOf));
            created += n == null ? 0 : n;
            processed++;
        }
        int penalties = assessPenalties(asOf);
        audit.record(AuditCategory.FINANCE, "ECHEANCES_GENEREES", "Obligation", asOf.toString(),
                Map.of("date", asOf.toString(), "echeances", created, "penalites", penalties, "membres", processed));
        return new ContributionDtos.GenerationResult(asOf, created, penalties, processed);
    }

    @Transactional
    public int generateForMember(Long memberId, LocalDate asOf) {
        Member m = members.lockById(memberId).orElseThrow(() -> BusinessException.notFound("Membre"));
        if (m.getContributionPlanId() == null
                || !props.contribution().generateForStatuses().contains(m.getStatus().name())) {
            return 0;
        }
        ContributionPlan plan = plans.findById(m.getContributionPlanId()).orElseThrow();
        if (plan.getStatus() == PlanStatus.BROUILLON) {
            return 0;
        }
        LocalDate effective = plan.getValidFrom().isAfter(m.getMembershipDate()) ? plan.getValidFrom() : m.getMembershipDate();
        int created = 0;
        for (ScheduleCalculator.Period p : ScheduleCalculator.periods(plan.getPeriodicity(), plan.getDueDayOfMonth(),
                effective, plan.getValidTo(), asOf.plusDays(HORIZON_DAYS))) {
            if (obligations.existsByMemberIdAndPlanIdAndPeriodStartAndKind(m.getId(), plan.getId(), p.start(),
                    ObligationKind.COTISATION)) {
                continue;
            }
            MemberObligation o = new MemberObligation();
            o.setMemberId(m.getId());
            o.setPlanId(plan.getId());
            o.setKind(ObligationKind.COTISATION);
            o.setPeriodLabel(p.label());
            o.setPeriodStart(p.start());
            o.setPeriodEnd(p.end());
            o.setDueDate(p.dueDate());
            o.setAmountDue(plan.getAmount());
            o.setGraceDays(plan.getGraceDays());
            o.setPenaltyMethod(plan.getPenaltyMethod());
            o.setPenaltyValue(plan.getPenaltyValue());
            o.setPenaltyCap(plan.getPenaltyCap());
            o.setRoundingMode(plan.getRoundingMode());
            obligations.save(o);
            created++;
        }
        if (created > 0) {
            obligations.flush();
            allocator.applyCredit(m.getId());
        }
        return created;
    }

    // ------------------------------------------------------------------ pénalités

    /** Applique les pénalités dues à la date donnée. Rejouable sans doublon (unicité parent + rang). */
    public int assessPenalties(LocalDate asOf) {
        int created = 0;
        for (MemberObligation o : obligations.findPenaltyCandidates(asOf)) {
            if (!PenaltyCalculator.assessmentDate(o.getDueDate(), o.getGraceDays(), 1).isAfter(asOf)) {
                Integer n = tx.execute(s -> assessFor(o.getId(), asOf));
                created += n == null ? 0 : n;
            }
        }
        return created;
    }

    private int assessFor(Long obligationId, LocalDate asOf) {
        MemberObligation o = obligations.findById(obligationId).orElseThrow();
        Member m = members.lockById(o.getMemberId()).orElseThrow();
        Map<Integer, MemberObligation> existing = obligations.findByParentIdOrderByPenaltySequenceAsc(o.getId()).stream()
                .collect(Collectors.toMap(MemberObligation::getPenaltySequence, x -> x));
        List<PenaltyCalculator.Assessment> list = PenaltyCalculator.assess(o.getDueDate(), o.getGraceDays(),
                o.getPenaltyMethod(), o.getPenaltyValue(), o.getPenaltyCap(), o.getRoundingMode(), asOf,
                day -> o.getAmountDue().subtract(allocations.sumForObligationBefore(o.getId(),
                        day.atStartOfDay(ClockConfig.DAKAR).toInstant())));
        int created = 0;
        for (PenaltyCalculator.Assessment a : list) {
            if (existing.containsKey(a.sequence())) {
                continue;
            }
            MemberObligation p = new MemberObligation();
            p.setMemberId(o.getMemberId());
            p.setPlanId(o.getPlanId());
            p.setKind(ObligationKind.PENALITE);
            p.setParentId(o.getId());
            p.setPenaltySequence(a.sequence());
            p.setPeriodLabel("Pénalité n° " + a.sequence() + " — " + o.getPeriodLabel());
            p.setPeriodStart(o.getPeriodStart());
            p.setPeriodEnd(o.getPeriodEnd());
            p.setDueDate(a.assessmentDate());
            p.setAmountDue(a.amount());
            p.setGraceDays(0);
            p.setPenaltyMethod(PenaltyMethod.AUCUNE);
            p.setPenaltyValue(BigDecimal.ZERO);
            p.setRoundingMode(o.getRoundingMode());
            obligations.save(p);
            created++;
            audit.record(AuditCategory.FINANCE, "PENALITE_APPLIQUEE", "MemberObligation", o.getId(),
                    Map.of("membre", m.getMemberNumber(), "rang", a.sequence(), "base", a.base(),
                            "montant", a.amount(), "date", a.assessmentDate().toString()));
        }
        if (created > 0) {
            obligations.flush();
            allocator.applyCredit(o.getMemberId());
        }
        return created;
    }

    // ------------------------------------------------------------------ ajustements

    /**
     * Ajustement explicite d'une échéance : remise de pénalité, annulation ou correction de montant.
     * Toujours motivé, tracé dans obligation_adjustment et dans le journal d'audit.
     */
    @Transactional
    public MemberObligation adjust(Long obligationId, ContributionDtos.AdjustmentRequest r, CurrentUser user) {
        MemberObligation o = obligations.lockById(obligationId).orElseThrow(() -> BusinessException.notFound("Échéance"));
        members.lockById(o.getMemberId());
        if (o.getStatus() == ObligationStatus.ANNULEE) {
            throw BusinessException.rule("ETAT_INVALIDE", "Échéance déjà annulée");
        }
        BigDecimal newAmount = Money.requireWholeXof(r.newAmount(), "Le nouveau montant");
        BigDecimal previous = o.getAmountDue();
        switch (r.type()) {
            case REMISE_PENALITE -> {
                if (o.getKind() != ObligationKind.PENALITE) {
                    throw BusinessException.rule("TYPE_INVALIDE", "Une remise ne concerne qu'une pénalité");
                }
                if (newAmount.compareTo(previous) >= 0) {
                    throw BusinessException.rule("REMISE_INVALIDE", "Le montant après remise doit être inférieur au montant initial");
                }
            }
            case ANNULATION -> {
                if (o.getAmountPaid().signum() > 0) {
                    throw BusinessException.rule("ANNULATION_IMPOSSIBLE",
                            "Une échéance partiellement payée ne peut pas être annulée : utilisez une correction de montant");
                }
                newAmount = BigDecimal.ZERO;
            }
            case CORRECTION_MONTANT -> {
                if (r.decisionReference() == null || r.decisionReference().isBlank()) {
                    throw BusinessException.rule("DECISION_REQUISE",
                            "Une correction rétroactive exige la référence de la décision qui l'autorise");
                }
            }
        }
        if (newAmount.compareTo(o.getAmountPaid()) < 0) {
            throw BusinessException.rule("MONTANT_INFERIEUR_PAYE",
                    "Le nouveau montant est inférieur aux sommes déjà imputées ; un remboursement est nécessaire");
        }
        ObligationAdjustment a = new ObligationAdjustment();
        a.setObligationId(o.getId());
        a.setAdjustmentType(r.type());
        a.setPreviousAmount(previous);
        a.setNewAmount(newAmount);
        a.setReason(r.reason());
        a.setDecisionReference(r.decisionReference());
        a.setPerformedBy(user.userId());
        adjustments.save(a);
        if (r.type() == ObligationAdjustment.Type.ANNULATION) {
            o.setStatus(ObligationStatus.ANNULEE);
            o.setCancelReason(r.reason());
        } else {
            o.setAmountDue(newAmount);
            o.refreshStatus();
        }
        audit.record(AuditCategory.FINANCE, "ECHEANCE_AJUSTEE", "MemberObligation", o.getId(),
                Map.of("type", r.type().name(), "avant", previous, "apres", newAmount, "motif", r.reason(),
                        "decision", String.valueOf(r.decisionReference())));
        return o;
    }

    // ------------------------------------------------------------------ consultation

    @Transactional(readOnly = true)
    public List<MemberObligation> forMember(Long memberId) {
        return obligations.findByMemberIdOrderByDueDateAscIdAsc(memberId);
    }

    @Transactional(readOnly = true)
    public MemberFinancialSummary summary(Long memberId) {
        Member m = members.findById(memberId).orElseThrow(() -> BusinessException.notFound("Membre"));
        LocalDate today = today();
        List<MemberObligation> all = obligations.findByMemberIdOrderByDueDateAscIdAsc(memberId);
        BigDecimal due = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal outstanding = BigDecimal.ZERO;
        BigDecimal overdue = BigDecimal.ZERO;
        BigDecimal penalties = BigDecimal.ZERO;
        long overdueCount = 0;
        LocalDate oldest = null;
        for (MemberObligation o : all) {
            if (o.getStatus() == ObligationStatus.ANNULEE) {
                continue;
            }
            due = due.add(o.getAmountDue());
            paid = paid.add(o.getAmountPaid());
            BigDecimal out = o.outstanding();
            outstanding = outstanding.add(out);
            if (o.getKind() == ObligationKind.PENALITE) {
                penalties = penalties.add(out);
            }
            if (out.signum() > 0 && o.getDueDate().isBefore(today)) {
                overdue = overdue.add(out);
                overdueCount++;
                if (oldest == null || o.getDueDate().isBefore(oldest)) {
                    oldest = o.getDueDate();
                }
            }
        }
        ContributionPlan plan = m.getContributionPlanId() == null ? null : plans.findById(m.getContributionPlanId()).orElse(null);
        List<ObligationResponse> upcoming = obligations.upcomingForMember(memberId, today, PageRequest.of(0, 3)).stream()
                .map(o -> ObligationResponse.of(o, today)).toList();
        return new MemberFinancialSummary(m.getId(), m.getMemberNumber(), plan == null ? null : plan.getName(),
                plan == null ? null : plan.getAmount(), due, paid, outstanding, overdue, penalties,
                transactions.creditBalance(memberId), overdueCount, oldest, overdue.signum() == 0, upcoming, today);
    }
}
