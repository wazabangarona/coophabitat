package sn.coophabitat.me;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import sn.coophabitat.admin.AdminDtos.DataRequestCreate;
import sn.coophabitat.admin.AdminDtos.DataRequestResponse;
import sn.coophabitat.admin.AdminService;
import sn.coophabitat.audit.AuditCategory;
import sn.coophabitat.audit.AuditService;
import sn.coophabitat.common.Downloads;
import sn.coophabitat.common.Json;
import sn.coophabitat.contribution.ContributionDtos;
import sn.coophabitat.contribution.ObligationService;
import sn.coophabitat.member.*;
import sn.coophabitat.member.MemberDtos.ContactUpdateRequest;
import sn.coophabitat.member.MemberDtos.MemberResponse;
import sn.coophabitat.payment.PaymentMapper;
import sn.coophabitat.payment.PaymentTransactionRepository;
import sn.coophabitat.receipt.ReceiptRepository;
import sn.coophabitat.security.CurrentUserProvider;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Espace personnel du membre connecté : uniquement ses propres données. */
@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Espace membre")
public class MeController {

    private final MemberService members;
    private final ObligationService obligations;
    private final PaymentTransactionRepository transactions;
    private final PaymentMapper paymentMapper;
    private final ReceiptRepository receipts;
    private final MembershipStatusHistoryRepository history;
    private final AdminService admin;
    private final AuditService audit;

    public MeController(MemberService members, ObligationService obligations, PaymentTransactionRepository transactions,
                        PaymentMapper paymentMapper, ReceiptRepository receipts, MembershipStatusHistoryRepository history,
                        AdminService admin, AuditService audit) {
        this.members = members;
        this.obligations = obligations;
        this.transactions = transactions;
        this.paymentMapper = paymentMapper;
        this.receipts = receipts;
        this.history = history;
        this.admin = admin;
        this.audit = audit;
    }

    @GetMapping("/profile")
    public MemberResponse profile() {
        return withoutInternalNotes(members.get(CurrentUserProvider.requireMemberId()));
    }

    @PutMapping("/contact")
    @Operation(summary = "Met à jour ses coordonnées (courriel, téléphone, adresse)")
    public MemberResponse updateContact(@Valid @RequestBody ContactUpdateRequest body) {
        return withoutInternalNotes(members.updateContact(CurrentUserProvider.requireMemberId(), body));
    }

    @GetMapping("/summary")
    public ContributionDtos.MemberFinancialSummary summary() {
        return obligations.summary(CurrentUserProvider.requireMemberId());
    }

    @GetMapping("/obligations")
    public List<ContributionDtos.ObligationResponse> obligations() {
        var today = obligations.today();
        return obligations.forMember(CurrentUserProvider.requireMemberId()).stream()
                .map(o -> ContributionDtos.ObligationResponse.of(o, today)).toList();
    }

    @GetMapping("/data-requests")
    public List<DataRequestResponse> dataRequests() {
        return admin.dataRequests(CurrentUserProvider.requireMemberId());
    }

    @PostMapping("/data-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public List<DataRequestResponse> fileDataRequest(@Valid @RequestBody DataRequestCreate body) {
        Long memberId = CurrentUserProvider.requireMemberId();
        admin.fileDataRequest(memberId, body);
        return admin.dataRequests(memberId);
    }

    /** Export de portabilité : les données personnelles et financières du membre, au format JSON. */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export() {
        Long memberId = CurrentUserProvider.requireMemberId();
        Member m = members.get(memberId);
        var today = obligations.today();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("genere_le", Instant.now().toString());
        data.put("profil", withoutInternalNotes(m));
        data.put("historique_statut", history.findByMemberIdOrderByChangedAtAscIdAsc(memberId).stream()
                .map(h -> Map.of("de", String.valueOf(h.getFromStatus()), "vers", h.getToStatus().name(),
                        "motif", h.getReason(), "date", h.getChangedAt().toString())).toList());
        data.put("echeances", obligations.forMember(memberId).stream()
                .map(o -> ContributionDtos.ObligationResponse.of(o, today)).toList());
        data.put("paiements", transactions.findByMemberIdOrderByConfirmedAtAsc(memberId).stream()
                .map(paymentMapper::transaction).toList());
        data.put("recus", receipts.findByMemberIdOrderByIssuedAtDesc(memberId).stream()
                .map(r -> Map.of("numero", r.getReceiptNumber(), "emis_le", r.getIssuedAt().toString(),
                        "simulation", r.isSimulated())).toList());
        audit.record(AuditCategory.DONNEES, "EXPORT_DONNEES_PERSONNELLES", "Member", memberId, Map.of());
        return Downloads.file(Json.write(data).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "mes-donnees-" + m.getMemberNumber() + ".json", MediaType.APPLICATION_JSON, false);
    }

    private MemberResponse withoutInternalNotes(Member m) {
        MemberResponse r = MemberResponse.of(m, true);
        return new MemberResponse(r.id(), r.memberNumber(), r.firstName(), r.lastName(), r.email(), r.phone(), r.address(),
                r.city(), r.membershipDate(), r.memberType(), r.status(), r.contributionPlanId(), null, r.demo(), true,
                r.createdAt(), r.updatedAt(), r.version());
    }
}
