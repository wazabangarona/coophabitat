package sn.coophabitat.contribution;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import sn.coophabitat.contribution.ContributionDtos.*;
import sn.coophabitat.member.MemberRepository;
import sn.coophabitat.security.CurrentUserProvider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/contributions")
@Tag(name = "Cotisations")
public class ContributionController {

    private final PlanService plans;
    private final ObligationService obligations;
    private final ObligationAdjustmentRepository adjustments;
    private final MemberRepository members;

    public ContributionController(PlanService plans, ObligationService obligations,
                                  ObligationAdjustmentRepository adjustments, MemberRepository members) {
        this.plans = plans;
        this.obligations = obligations;
        this.adjustments = adjustments;
        this.members = members;
    }

    @GetMapping("/plans")
    @PreAuthorize("hasAuthority('PLAN_READ')")
    public List<PlanResponse> listPlans() {
        var all = members.findAll();
        return plans.list().stream().map(p -> PlanResponse.of(p,
                all.stream().filter(m -> p.getId().equals(m.getContributionPlanId())).count())).toList();
    }

    @GetMapping("/plans/{id}")
    @PreAuthorize("hasAuthority('PLAN_READ')")
    public PlanResponse getPlan(@PathVariable Long id) {
        return PlanResponse.of(plans.get(id), 0);
    }

    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PLAN_WRITE')")
    public PlanResponse createPlan(@Valid @RequestBody PlanRequest body) {
        return PlanResponse.of(plans.create(body), 0);
    }

    @PutMapping("/plans/{id}")
    @PreAuthorize("hasAuthority('PLAN_WRITE')")
    @Operation(summary = "Modification d'un plan en brouillon uniquement (un plan actif est figé)")
    public PlanResponse updatePlan(@PathVariable Long id, @Valid @RequestBody PlanRequest body) {
        return PlanResponse.of(plans.update(id, body), 0);
    }

    @PostMapping("/plans/{id}/activate")
    @PreAuthorize("hasAuthority('PLAN_WRITE')")
    public PlanResponse activate(@PathVariable Long id) {
        return PlanResponse.of(plans.activate(id), 0);
    }

    @PostMapping("/plans/{id}/archive")
    @PreAuthorize("hasAuthority('PLAN_WRITE')")
    public PlanResponse archive(@PathVariable Long id, @Valid @RequestBody ArchivePlanRequest body) {
        return PlanResponse.of(plans.archive(id, body.validTo(), body.reason()), 0);
    }

    @PostMapping("/generate")
    @PreAuthorize("hasAuthority('OBLIGATION_GENERATE')")
    @Operation(summary = "Génère les échéances manquantes et applique les pénalités à la date donnée (idempotent)")
    public GenerationResult generate(@RequestParam(required = false) LocalDate asOf) {
        LocalDate date = asOf == null ? obligations.today() : asOf;
        if (date.isAfter(obligations.today())) {
            throw sn.coophabitat.common.BusinessException.badRequest("DATE_FUTURE",
                    "Les pénalités ne peuvent pas être appliquées à une date future");
        }
        return obligations.generateAll(date);
    }

    @PostMapping("/obligations/{id}/adjust")
    @PreAuthorize("hasAuthority('OBLIGATION_ADJUST')")
    @Operation(summary = "Ajustement motivé et audité (remise de pénalité, annulation, correction autorisée)")
    public ObligationResponse adjust(@PathVariable Long id, @Valid @RequestBody AdjustmentRequest body) {
        return ObligationResponse.of(obligations.adjust(id, body, CurrentUserProvider.require()), obligations.today());
    }

    @GetMapping("/obligations/{id}/adjustments")
    @PreAuthorize("hasAuthority('MEMBER_READ_ALL')")
    public List<AdjustmentResponse> adjustments(@PathVariable Long id) {
        return adjustments.findByObligationIdOrderByIdAsc(id).stream().map(AdjustmentResponse::of).toList();
    }

    @PostMapping("/penalties/simulate")
    @PreAuthorize("hasAuthority('PLAN_READ')")
    @Operation(summary = "Simulation transparente du calcul de pénalité (aucune écriture)")
    public PenaltySimulationResponse simulate(@Valid @RequestBody PenaltySimulationRequest r) {
        var list = PenaltyCalculator.assess(r.dueDate(), r.graceDays(), r.method(), r.value(), r.cap(), r.rounding(),
                r.asOf(), d -> r.principal());
        return new PenaltySimulationResponse(list, list.stream().map(PenaltyCalculator.Assessment::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }
}
