package sn.coophabitat.contribution;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.coophabitat.audit.AuditCategory;
import sn.coophabitat.audit.AuditService;
import sn.coophabitat.common.BusinessException;
import sn.coophabitat.common.Money;
import sn.coophabitat.contribution.ContributionDtos.PlanRequest;
import sn.coophabitat.security.CurrentUserProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plans de cotisation. Un plan actif est figé : pour changer un montant ou une règle de pénalité, on crée un
 * nouveau plan (nouvelle version) et on archive l'ancien à une date donnée. Les échéances déjà générées
 * conservent leurs règles d'origine.
 */
@Service
public class PlanService {

    private final ContributionPlanRepository plans;
    private final AuditService audit;

    public PlanService(ContributionPlanRepository plans, AuditService audit) {
        this.plans = plans;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<ContributionPlan> list() {
        return plans.findAllByOrderByValidFromDesc();
    }

    @Transactional(readOnly = true)
    public ContributionPlan get(Long id) {
        return plans.findById(id).orElseThrow(() -> BusinessException.notFound("Plan de cotisation"));
    }

    @Transactional
    public ContributionPlan create(PlanRequest r) {
        if (plans.findByCode(r.code()).isPresent()) {
            throw BusinessException.conflict("CODE_EXISTANT", "Un plan porte déjà ce code");
        }
        ContributionPlan p = new ContributionPlan();
        apply(p, r);
        p.setCreatedBy(CurrentUserProvider.current().map(u -> u.userId()).orElse(null));
        p = plans.save(p);
        audit.record(AuditCategory.FINANCE, "PLAN_CREE", "ContributionPlan", p.getId(), describe(p));
        return p;
    }

    @Transactional
    public ContributionPlan update(Long id, PlanRequest r) {
        ContributionPlan p = get(id);
        if (p.getStatus() != PlanStatus.BROUILLON) {
            throw BusinessException.rule("PLAN_FIGE",
                    "Un plan actif ou archivé ne peut pas être modifié : créez une nouvelle version du plan");
        }
        if (!p.getCode().equals(r.code()) && plans.findByCode(r.code()).isPresent()) {
            throw BusinessException.conflict("CODE_EXISTANT", "Un plan porte déjà ce code");
        }
        apply(p, r);
        audit.record(AuditCategory.FINANCE, "PLAN_MODIFIE", "ContributionPlan", p.getId(), describe(p));
        return p;
    }

    @Transactional
    public ContributionPlan activate(Long id) {
        ContributionPlan p = get(id);
        if (p.getStatus() != PlanStatus.BROUILLON) {
            throw BusinessException.rule("ETAT_INVALIDE", "Seul un plan en brouillon peut être activé");
        }
        if (p.getAdoptedReference() == null || p.getAdoptedReference().isBlank()) {
            throw BusinessException.rule("ADOPTION_REQUISE",
                    "Indiquez la référence de la décision d'adoption (ex. résolution d'assemblée générale)");
        }
        p.setStatus(PlanStatus.ACTIF);
        audit.record(AuditCategory.FINANCE, "PLAN_ACTIVE", "ContributionPlan", p.getId(), describe(p));
        return p;
    }

    @Transactional
    public ContributionPlan archive(Long id, java.time.LocalDate validTo, String reason) {
        ContributionPlan p = get(id);
        if (p.getStatus() == PlanStatus.ARCHIVE) {
            throw BusinessException.rule("ETAT_INVALIDE", "Plan déjà archivé");
        }
        if (validTo.isBefore(p.getValidFrom())) {
            throw BusinessException.badRequest("DATE_INVALIDE", "La date de fin précède la date de début");
        }
        p.setValidTo(validTo);
        p.setStatus(PlanStatus.ARCHIVE);
        audit.record(AuditCategory.FINANCE, "PLAN_ARCHIVE", "ContributionPlan", p.getId(),
                Map.of("fin", validTo.toString(), "motif", reason));
        return p;
    }

    private void apply(ContributionPlan p, PlanRequest r) {
        if (r.validTo() != null && r.validTo().isBefore(r.validFrom())) {
            throw BusinessException.badRequest("DATE_INVALIDE", "La date de fin précède la date de début");
        }
        if (r.penaltyMethod() == PenaltyMethod.POURCENTAGE_MENSUEL && r.penaltyValue().compareTo(java.math.BigDecimal.valueOf(100)) > 0) {
            throw BusinessException.badRequest("TAUX_INVALIDE", "Le taux de pénalité ne peut pas dépasser 100 %");
        }
        if (r.penaltyMethod() == PenaltyMethod.FORFAIT) {
            Money.requireWholeXof(r.penaltyValue(), "Le forfait de pénalité");
        }
        p.setCode(r.code());
        p.setName(r.name());
        p.setDescription(r.description());
        p.setAmount(Money.requirePositiveXof(r.amount(), "Le montant de cotisation"));
        p.setPeriodicity(r.periodicity());
        p.setDueDayOfMonth(r.dueDayOfMonth());
        p.setValidFrom(r.validFrom());
        p.setValidTo(r.validTo());
        p.setGraceDays(r.graceDays());
        p.setPenaltyMethod(r.penaltyMethod());
        p.setPenaltyValue(r.penaltyMethod() == PenaltyMethod.AUCUNE ? java.math.BigDecimal.ZERO : r.penaltyValue());
        p.setPenaltyCap(r.penaltyCap() == null ? null : Money.requireWholeXof(r.penaltyCap(), "Le plafond"));
        p.setRoundingMode(r.roundingMode());
        p.setAdoptedReference(r.adoptedReference());
    }

    private static Map<String, Object> describe(ContributionPlan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", p.getCode());
        m.put("montant", p.getAmount());
        m.put("periodicite", p.getPeriodicity().name());
        m.put("jour_echeance", p.getDueDayOfMonth());
        m.put("grace", p.getGraceDays());
        m.put("penalite", p.getPenaltyMethod().name() + " " + p.getPenaltyValue());
        m.put("plafond", String.valueOf(p.getPenaltyCap()));
        m.put("statut", p.getStatus().name());
        return m;
    }
}
