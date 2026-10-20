package sn.coophabitat.member;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import sn.coophabitat.audit.AuditCategory;
import sn.coophabitat.audit.AuditService;
import sn.coophabitat.common.Downloads;
import sn.coophabitat.common.PageResponse;
import sn.coophabitat.contribution.ContributionDtos;
import sn.coophabitat.contribution.ObligationService;
import sn.coophabitat.member.MemberDtos.*;
import sn.coophabitat.reporting.ExcelExporter;
import sn.coophabitat.security.CurrentUserProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/members")
@Tag(name = "Membres")
public class MemberController {

    private final MemberService service;
    private final ObligationService obligations;
    private final AuditService audit;

    public MemberController(MemberService service, ObligationService obligations, AuditService audit) {
        this.service = service;
        this.obligations = obligations;
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('MEMBER_READ_ALL')")
    @Operation(summary = "Recherche paginée des membres (filtres : texte, statut, type, sans plan de cotisation)")
    public PageResponse<MemberListItem> search(@RequestParam(required = false) String q,
                                               @RequestParam(required = false) MemberStatus status,
                                               @RequestParam(required = false) MemberType type,
                                               @RequestParam(defaultValue = "false") boolean withoutPlan,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by("lastName").and(Sort.by("firstName")));
        return PageResponse.of(service.search(q, status, type, withoutPlan, pageable), MemberListItem::of);
    }

    @GetMapping("/applications")
    @PreAuthorize("hasAuthority('MEMBER_READ_ALL')")
    @Operation(summary = "Demandes d'adhésion à examiner (dossiers en attente), les plus anciennes d'abord")
    public ApplicationQueue applications() {
        return service.applications(CurrentUserProvider.require());
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('MEMBER_STATUS_CHANGE')")
    @Operation(summary = "Approuver une demande d'adhésion : affectation du plan et activation en une décision tracée")
    public MemberResponse approve(@PathVariable Long id, @Valid @RequestBody ApprovalRequest body) {
        return MemberResponse.of(service.approve(id, body, CurrentUserProvider.require()), service.hasAccount(id));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('MEMBER_STATUS_CHANGE')")
    @Operation(summary = "Refuser une demande d'adhésion (motif obligatoire, notifié à la personne)")
    public MemberResponse reject(@PathVariable Long id, @Valid @RequestBody RejectionRequest body) {
        return MemberResponse.of(service.reject(id, body, CurrentUserProvider.require()), service.hasAccount(id));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('MEMBER_READ_ALL')")
    public MemberResponse get(@PathVariable Long id) {
        return MemberResponse.of(service.get(id), service.hasAccount(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('MEMBER_WRITE')")
    public CreatedMember create(@Valid @RequestBody CreateMemberRequest body) {
        return service.create(body, CurrentUserProvider.require());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MEMBER_WRITE')")
    public MemberResponse update(@PathVariable Long id, @Valid @RequestBody UpdateMemberRequest body) {
        return MemberResponse.of(service.update(id, body), service.hasAccount(id));
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('MEMBER_STATUS_CHANGE')")
    @Operation(summary = "Changement de statut motivé (historisé, sans effacement des obligations)")
    public MemberResponse changeStatus(@PathVariable Long id, @Valid @RequestBody StatusChangeRequest body) {
        return MemberResponse.of(service.changeStatus(id, body, CurrentUserProvider.require()), service.hasAccount(id));
    }

    @PostMapping("/{id}/plan")
    @PreAuthorize("hasAuthority('PLAN_WRITE')")
    public MemberResponse assignPlan(@PathVariable Long id, @Valid @RequestBody AssignPlanRequest body) {
        return MemberResponse.of(service.assignPlan(id, body.planId(), CurrentUserProvider.require()), service.hasAccount(id));
    }

    @PostMapping("/plan")
    @PreAuthorize("hasAuthority('PLAN_WRITE')")
    @Operation(summary = "Affecter un plan de cotisation à plusieurs membres (chaque affectation est journalisée)")
    public BulkPlanResult assignPlanToMany(@Valid @RequestBody BulkPlanRequest body) {
        return service.assignPlan(body.memberIds(), body.planId(), CurrentUserProvider.require());
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("hasAuthority('MEMBER_READ_ALL')")
    public List<StatusHistoryItem> history(@PathVariable Long id) {
        return service.history(id).stream().map(StatusHistoryItem::of).toList();
    }

    @GetMapping("/{id}/summary")
    @PreAuthorize("hasAuthority('MEMBER_READ_ALL')")
    public ContributionDtos.MemberFinancialSummary summary(@PathVariable Long id) {
        return obligations.summary(id);
    }

    @GetMapping("/{id}/obligations")
    @PreAuthorize("hasAuthority('MEMBER_READ_ALL')")
    public List<ContributionDtos.ObligationResponse> memberObligations(@PathVariable Long id) {
        var today = obligations.today();
        return obligations.forMember(id).stream().map(o -> ContributionDtos.ObligationResponse.of(o, today)).toList();
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('MEMBER_EXPORT')")
    @Operation(summary = "Export Excel de la liste des membres (journalisé)")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) MemberStatus status) {
        List<List<Object>> rows = new ArrayList<>();
        service.search(null, status, null, false, PageRequest.of(0, 10_000, Sort.by("memberNumber"))).forEach(m -> rows.add(List.of(
                m.getMemberNumber(), m.getLastName(), m.getFirstName(), m.getMemberType().name(), m.getStatus().name(),
                m.getMembershipDate(), nz(m.getCity()), nz(m.getPhone()), nz(m.getEmail()), m.isDemo())));
        audit.record(AuditCategory.DONNEES, "EXPORT_MEMBRES", "Member", null,
                Map.of("lignes", rows.size(), "statut", String.valueOf(status)));
        byte[] xlsx = ExcelExporter.export(List.of(new ExcelExporter.Sheet("Membres",
                List.of("Export CoopHabitat — données personnelles : diffusion restreinte au bureau",
                        "Les lignes marquées « démo » sont fictives"),
                List.of("N° adhérent", "Nom", "Prénom", "Type", "Statut", "Adhésion", "Ville", "Téléphone", "E-mail", "Démo"),
                rows)));
        return Downloads.file(xlsx, "membres.xlsx", Downloads.XLSX, false);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
