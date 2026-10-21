package sn.coophabitat.payment;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import sn.coophabitat.admin.CoopSettingsRepository;
import sn.coophabitat.audit.AuditCategory;
import sn.coophabitat.audit.AuditService;
import sn.coophabitat.common.BusinessException;
import sn.coophabitat.common.Downloads;
import sn.coophabitat.common.PageResponse;
import sn.coophabitat.contribution.MemberObligationRepository;
import sn.coophabitat.member.MemberRepository;
import sn.coophabitat.payment.PaymentDtos.*;
import sn.coophabitat.payment.provider.PaymentProviderRegistry;
import sn.coophabitat.reporting.ExcelExporter;
import sn.coophabitat.security.CurrentUser;
import sn.coophabitat.security.CurrentUserProvider;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Paiements")
public class PaymentController {

    private final PaymentService service;
    private final PaymentIntentRepository intents;
    private final PaymentTransactionRepository transactions;
    private final PaymentAllocationRepository allocations;
    private final MemberObligationRepository obligations;
    private final MemberRepository members;
    private final PaymentProviderRegistry providers;
    private final PaymentMapper mapper;
    private final CoopSettingsRepository settings;
    private final AuditService audit;

    public PaymentController(PaymentService service, PaymentIntentRepository intents,
                             PaymentTransactionRepository transactions, PaymentAllocationRepository allocations,
                             MemberObligationRepository obligations, MemberRepository members,
                             PaymentProviderRegistry providers, PaymentMapper mapper,
                             CoopSettingsRepository settings, AuditService audit) {
        this.service = service;
        this.intents = intents;
        this.transactions = transactions;
        this.allocations = allocations;
        this.obligations = obligations;
        this.members = members;
        this.providers = providers;
        this.mapper = mapper;
        this.settings = settings;
        this.audit = audit;
    }

    @GetMapping("/providers")
    @Operation(summary = "Fournisseurs de paiement et leur disponibilité réelle")
    public List<ProviderInfo> providers() {
        return providers.all().stream()
                .map(p -> new ProviderInfo(p.code(), p.available(), p.simulated(),
                        p.available() ? (p.simulated() ? "SIMULATION — AUCUN ARGENT RÉEL TRANSFÉRÉ" : "Disponible")
                                : p.unavailableReason()))
                .toList();
    }

    @GetMapping("/instructions")
    @Operation(summary = "Comment payer la coopérative (consignes du bureau) et paiement en ligne disponible ou non")
    public PaymentInstructions instructions() {
        boolean online = providers.all().stream().anyMatch(p -> p.available());
        return new PaymentInstructions(settings.current().getPaymentInstructions(),
                List.of("WAVE", "ORANGE_MONEY", "VIREMENT", "CHEQUE"), online);
    }

    @PutMapping("/instructions")
    @PreAuthorize("hasAnyAuthority('PAYMENT_MANUAL_RECORD', 'SETTINGS_ADMIN')")
    @Transactional
    @Operation(summary = "Met à jour les consignes de paiement affichées aux membres (numéro Wave, compte bancaire…)")
    public PaymentInstructions updateInstructions(@Valid @RequestBody PaymentInstructionsRequest body) {
        var s = settings.current();
        String text = body.instructions() == null || body.instructions().isBlank() ? null : body.instructions().trim();
        s.setPaymentInstructions(text);
        settings.save(s);
        audit.record(AuditCategory.FINANCE, "CONSIGNES_PAIEMENT_MODIFIEES", "CoopSettings", 1L,
                java.util.Map.of("renseignees", text != null));
        return instructions();
    }

    @PostMapping("/declarations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SELF_SERVICE')")
    @Operation(summary = "Le membre déclare un paiement déjà fait (Wave, Orange Money, virement, chèque) ; compté après vérification par le bureau")
    public IntentResponse declarePayment(@RequestHeader("Idempotency-Key") String key,
                                  @Valid @RequestBody PaymentDeclarationRequest body) {
        CurrentUser user = CurrentUserProvider.require();
        return mapper.intent(service.declare(CurrentUserProvider.requireMemberId(), body, key, user));
    }

    @GetMapping("/declarations/mine")
    @PreAuthorize("hasAuthority('SELF_SERVICE')")
    @Operation(summary = "Paiements déclarés ou remis au bureau par le membre connecté, avec leur état de vérification")
    public List<IntentResponse> myDeclarations() {
        return intents.search(null, ProviderCode.MANUEL, CurrentUserProvider.requireMemberId(), null,
                PageRequest.of(0, 20)).map(mapper::intent).getContent();
    }

    @PostMapping("/intents")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SELF_SERVICE')")
    @Operation(summary = "Le membre connecté initie un paiement (idempotent via l'en-tête Idempotency-Key)")
    public IntentResponse createIntent(@RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody CreateIntentRequest body) {
        CurrentUser user = CurrentUserProvider.require();
        Long memberId = CurrentUserProvider.requireMemberId();
        return mapper.intent(service.createIntent(memberId, body, key, user));
    }

    @GetMapping("/intents/{id}")
    public IntentResponse getIntent(@PathVariable Long id) {
        PaymentIntent i = intents.findById(id).orElseThrow(() -> BusinessException.notFound("Paiement"));
        service.requireIntentAccess(i, CurrentUserProvider.require());
        return mapper.intent(i);
    }

    @GetMapping("/intents/by-reference/{reference}")
    public IntentResponse getIntentByReference(@PathVariable String reference) {
        PaymentIntent i = intents.findByReference(reference).orElseThrow(() -> BusinessException.notFound("Paiement"));
        service.requireIntentAccess(i, CurrentUserProvider.require());
        return mapper.intent(i);
    }

    @PostMapping("/intents/{id}/check")
    @Operation(summary = "Relit l'état du paiement auprès du fournisseur (reprise après indisponibilité)")
    public IntentResponse check(@PathVariable Long id) {
        PaymentIntent i = intents.findById(id).orElseThrow(() -> BusinessException.notFound("Paiement"));
        CurrentUser user = CurrentUserProvider.require();
        if (!user.isMember(i.getMemberId()) && !user.has(sn.coophabitat.security.Permission.PAYMENT_RECONCILE)) {
            throw BusinessException.forbidden("Accès refusé");
        }
        return mapper.intent(service.checkWithProvider(id));
    }

    @PostMapping("/simulation/{reference}")
    @PreAuthorize("hasAuthority('SELF_SERVICE')")
    @Operation(summary = "SIMULATION — AUCUN ARGENT RÉEL TRANSFÉRÉ : simule la réponse du fournisseur fictif")
    public IntentResponse simulate(@PathVariable String reference, @RequestBody SimulationRequest body) {
        return mapper.intent(service.simulate(reference, body.success(), CurrentUserProvider.require()));
    }

    @GetMapping("/intents")
    @PreAuthorize("hasAuthority('PAYMENT_READ_ALL')")
    public PageResponse<IntentResponse> searchIntents(@RequestParam(required = false) PaymentStatus status,
                                                      @RequestParam(required = false) ProviderCode provider,
                                                      @RequestParam(required = false) Long memberId,
                                                      @RequestParam(required = false) Boolean simulated,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(intents.search(status, provider, memberId, simulated,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100))), mapper::intent);
    }

    @GetMapping("/transactions/{id}")
    public TransactionResponse transaction(@PathVariable Long id) {
        PaymentTransaction t = transactions.findById(id).orElseThrow(() -> BusinessException.notFound("Transaction"));
        CurrentUserProvider.requireSelfOr(t.getMemberId(), sn.coophabitat.security.Permission.PAYMENT_READ_ALL);
        return mapper.transaction(t);
    }

    @GetMapping("/transactions/{id}/allocations")
    public List<AllocationResponse> transactionAllocations(@PathVariable Long id) {
        PaymentTransaction t = transactions.findById(id).orElseThrow(() -> BusinessException.notFound("Transaction"));
        CurrentUserProvider.requireSelfOr(t.getMemberId(), sn.coophabitat.security.Permission.PAYMENT_READ_ALL);
        return allocations.findByTransactionIdOrderByIdAsc(id).stream().map(a -> new AllocationResponse(
                a.getObligationId(), obligations.findById(a.getObligationId()).map(o -> o.getPeriodLabel()).orElse(null),
                a.getAmount(), a.getCreatedAt(), a.getReversalOfId() != null)).toList();
    }

    @GetMapping("/members/{memberId}/transactions")
    public PageResponse<TransactionResponse> memberTransactions(@PathVariable Long memberId,
                                                                @RequestParam(defaultValue = "0") int page,
                                                                @RequestParam(defaultValue = "20") int size) {
        CurrentUserProvider.requireSelfOr(memberId, sn.coophabitat.security.Permission.PAYMENT_READ_ALL);
        return PageResponse.of(transactions.findByMemberIdOrderByConfirmedAtDesc(memberId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100))), mapper::transaction);
    }

    @PostMapping("/manual")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PAYMENT_MANUAL_RECORD')")
    @Operation(summary = "Saisie d'un encaissement manuel : reste EN_VERIFICATION jusqu'à validation par une autre personne")
    public IntentResponse recordManual(@RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody ManualPaymentRequest body) {
        return mapper.intent(service.recordManual(body, key, CurrentUserProvider.require()));
    }

    @PostMapping("/manual/{id}/verify")
    @PreAuthorize("hasAuthority('PAYMENT_MANUAL_VERIFY')")
    public IntentResponse verifyManual(@PathVariable Long id, @Valid @RequestBody VerifyManualRequest body) {
        return mapper.intent(service.verifyManual(id, body.approve(), body.comment(), CurrentUserProvider.require()));
    }

    @PostMapping("/transactions/{id}/refund")
    @PreAuthorize("hasAuthority('PAYMENT_REFUND')")
    @Operation(summary = "Remboursement : contre-écritures, imputations contre-passées, aucune suppression")
    public TransactionResponse refund(@PathVariable Long id, @Valid @RequestBody RefundRequest body) {
        return mapper.transaction(service.refund(id, body.reason(), CurrentUserProvider.require()));
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('PAYMENT_READ_ALL')")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) Boolean simulated) {
        List<List<Object>> rows = new ArrayList<>();
        intents.search(null, null, null, simulated, PageRequest.of(0, 10_000)).forEach(i -> rows.add(List.of(
                i.getReference(), members.findById(i.getMemberId()).map(m -> m.getMemberNumber()).orElse(""),
                i.getAmount(), i.getProvider().name(), String.valueOf(i.getChannel()), i.getStatus().name(),
                i.isSimulated() ? "SIMULATION" : "RÉEL", i.getCreatedAt().toString(),
                i.getConfirmedAt() == null ? "" : i.getConfirmedAt().toString())));
        byte[] x = ExcelExporter.export(List.of(new ExcelExporter.Sheet("Paiements",
                List.of("Les lignes SIMULATION ne correspondent à aucun argent réel transféré",
                        "Seul l'état CONFIRME correspond à un paiement reçu"),
                List.of("Référence", "Adhérent", "Montant (XOF)", "Fournisseur", "Moyen", "État", "Nature", "Créé le", "Confirmé le"),
                rows)));
        return Downloads.file(x, "paiements.xlsx", Downloads.XLSX, false);
    }
}
