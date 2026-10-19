package sn.coophabitat.allocation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import sn.coophabitat.allocation.AllocationDtos.*;
import sn.coophabitat.security.CurrentUserProvider;

import java.util.List;

@RestController
@RequestMapping("/api/v1/allocations")
@Tag(name = "Attribution des lots")
public class AllocationController {

    private final AllocationService service;

    public AllocationController(AllocationService service) {
        this.service = service;
    }

    // ---------------------------------------------------------------- bureau

    @GetMapping
    @PreAuthorize("hasAuthority('ALLOCATION_READ_ALL')")
    public List<CampaignResponse> list() {
        return service.list().stream().map(service::toResponse).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ALLOCATION_READ_ALL')")
    public CampaignResponse get(@PathVariable Long id) {
        return service.toResponse(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ALLOCATION_MANAGE')")
    public CampaignResponse create(@Valid @RequestBody CampaignRequest body) {
        return service.toResponse(service.create(body, CurrentUserProvider.require()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ALLOCATION_MANAGE')")
    public CampaignResponse update(@PathVariable Long id, @Valid @RequestBody CampaignRequest body) {
        return service.toResponse(service.update(id, body));
    }

    @PutMapping("/{id}/lots")
    @PreAuthorize("hasAuthority('ALLOCATION_MANAGE')")
    public CampaignResponse setLots(@PathVariable Long id, @Valid @RequestBody LotsRequest body) {
        return service.toResponse(service.setLots(id, body.lotIds()));
    }

    @GetMapping("/{id}/commission")
    @PreAuthorize("hasAuthority('ALLOCATION_READ_ALL')")
    public List<CommissionMember> commission(@PathVariable Long id) {
        return service.commissionOf(id);
    }

    @PutMapping("/{id}/commission")
    @PreAuthorize("hasAuthority('ALLOCATION_MANAGE')")
    public List<CommissionMember> setCommission(@PathVariable Long id, @Valid @RequestBody CommissionRequest body) {
        return service.setCommission(id, body.members());
    }

    @PostMapping("/{id}/publish-criteria")
    @PreAuthorize("hasAuthority('ALLOCATION_MANAGE')")
    public CampaignResponse publishCriteria(@PathVariable Long id) {
        return service.toResponse(service.publishCriteria(id));
    }

    @PostMapping("/{id}/open")
    @PreAuthorize("hasAuthority('ALLOCATION_MANAGE')")
    public CampaignResponse open(@PathVariable Long id) {
        return service.toResponse(service.openApplications(id));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('ALLOCATION_MANAGE')")
    public CampaignResponse close(@PathVariable Long id) {
        return service.toResponse(service.closeApplications(id));
    }

    @PostMapping("/{id}/check-eligibility")
    @PreAuthorize("hasAuthority('ALLOCATION_MANAGE')")
    public CampaignResponse checkEligibility(@PathVariable Long id) {
        return service.toResponse(service.checkEligibility(id));
    }

    @PostMapping("/{id}/publish-list")
    @PreAuthorize("hasAuthority('ALLOCATION_MANAGE')")
    @Operation(summary = "Fige la liste, calcule les empreintes et publie l'engagement sur la graine du tirage")
    public CampaignResponse publishList(@PathVariable Long id) {
        return service.toResponse(service.publishList(id));
    }

    @GetMapping("/{id}/candidates")
    @PreAuthorize("hasAuthority('ALLOCATION_READ_ALL')")
    public List<CandidateResponse> candidates(@PathVariable Long id) {
        return service.candidateResponses(id);
    }

    @GetMapping("/{id}/results")
    @PreAuthorize("hasAuthority('ALLOCATION_READ_ALL')")
    public List<ResultResponse> results(@PathVariable Long id) {
        return service.resultResponses(id);
    }

    @GetMapping("/{id}/approvals")
    @PreAuthorize("hasAuthority('ALLOCATION_READ_ALL')")
    public List<ApprovalResponse> approvals(@PathVariable Long id) {
        return service.approvalResponses(id);
    }

    @GetMapping("/{id}/minutes")
    @PreAuthorize("hasAuthority('ALLOCATION_READ_ALL')")
    public List<MinutesResponse> minutes(@PathVariable Long id) {
        return service.minutesResponses(id);
    }

    @GetMapping("/{id}/contests")
    @PreAuthorize("hasAuthority('ALLOCATION_READ_ALL')")
    public List<ContestResponse> contests(@PathVariable Long id) {
        return service.contestResponses(id);
    }

    /** Les membres de commission n'ont pas forcément ALLOCATION_APPROVE : le service vérifie l'appartenance. */
    @PostMapping("/{id}/approvals")
    public CampaignResponse approve(@PathVariable Long id, @Valid @RequestBody ApprovalRequest body) {
        return service.toResponse(service.approve(id, body, CurrentUserProvider.require()));
    }

    @PostMapping("/{id}/execute")
    @PreAuthorize("hasAuthority('ALLOCATION_EXECUTE')")
    @Operation(summary = "Exécute une seule fois le tirage au sort validé")
    public CampaignResponse execute(@PathVariable Long id, @Valid @RequestBody(required = false) ExecuteRequest body) {
        return service.toResponse(service.executeDraw(id, body == null ? null : body.publicEntropy(), CurrentUserProvider.require()));
    }

    @PostMapping("/{id}/decisions")
    @ResponseStatus(HttpStatus.CREATED)
    public ResultResponse decide(@PathVariable Long id, @Valid @RequestBody CommissionDecisionRequest body) {
        return service.resultResponse(service.commissionDecide(id, body, CurrentUserProvider.require()));
    }

    @PostMapping("/{id}/minutes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ALLOCATION_MANAGE')")
    public MinutesResponse generateMinutes(@PathVariable Long id) {
        AllocationMinutes m = service.generateMinutes(id, CurrentUserProvider.require());
        return new MinutesResponse(m.getId(), m.getVersionNo(), m.getDocumentId(), m.getStatus(), m.getGeneratedAt(),
                m.getValidatedAt());
    }

    @PostMapping("/{id}/publish-results")
    @PreAuthorize("hasAuthority('ALLOCATION_MANAGE')")
    public CampaignResponse publishResults(@PathVariable Long id) {
        return service.toResponse(service.publishResults(id));
    }

    @PostMapping("/results/{resultId}/cancel")
    @PreAuthorize("hasAuthority('ALLOCATION_APPROVE')")
    @Operation(summary = "Demande ou valide l'annulation formelle d'un résultat (plusieurs validations distinctes)")
    public ResultResponse cancelResult(@PathVariable Long resultId, @Valid @RequestBody CancelRequest body) {
        return service.resultResponse(service.requestResultCancellation(resultId, body.reason(), CurrentUserProvider.require()));
    }

    @PostMapping("/contests/{contestId}/decision")
    @PreAuthorize("hasAuthority('ALLOCATION_CONTEST_DECIDE')")
    public ContestResponse decideContest(@PathVariable Long contestId, @Valid @RequestBody ContestDecisionRequest body) {
        return service.contestResponse(service.decideContest(contestId, body, CurrentUserProvider.require()));
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('ALLOCATION_MANAGE')")
    public CampaignResponse archive(@PathVariable Long id) {
        return service.toResponse(service.archive(id));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('ALLOCATION_MANAGE')")
    public CampaignResponse cancel(@PathVariable Long id, @Valid @RequestBody CancelRequest body) {
        return service.toResponse(service.cancel(id, body.reason()));
    }

    @GetMapping("/{id}/verify")
    @PreAuthorize("hasAuthority('ALLOCATION_VERIFY') or hasAuthority('ALLOCATION_READ_ALL')")
    @Operation(summary = "Recalcule le tirage à partir des données enregistrées et le compare aux résultats")
    public DrawVerification verify(@PathVariable Long id) {
        return service.verify(id);
    }

    // ---------------------------------------------------------------- espace membre

    @GetMapping("/me")
    public List<MemberCampaignView> mine() {
        return service.memberView(CurrentUserProvider.requireMemberId());
    }

    @PostMapping("/{id}/apply")
    @ResponseStatus(HttpStatus.CREATED)
    public CandidateResponse apply(@PathVariable Long id) {
        return service.candidateResponse(service.apply(id, CurrentUserProvider.requireMemberId()));
    }

    @PostMapping("/{id}/withdraw")
    public CandidateResponse withdraw(@PathVariable Long id) {
        return service.candidateResponse(service.withdraw(id, CurrentUserProvider.requireMemberId()));
    }

    @PostMapping("/{id}/contests")
    @ResponseStatus(HttpStatus.CREATED)
    public ContestResponse contest(@PathVariable Long id, @Valid @RequestBody ContestRequest body) {
        return service.contestResponse(service.fileContest(id, CurrentUserProvider.requireMemberId(), body));
    }
}
