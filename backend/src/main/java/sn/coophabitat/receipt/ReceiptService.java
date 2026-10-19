package sn.coophabitat.receipt;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sn.coophabitat.admin.CoopSettingsRepository;
import sn.coophabitat.common.BusinessException;
import sn.coophabitat.common.Hashing;
import sn.coophabitat.common.ReferenceGenerator;
import sn.coophabitat.config.AppProperties;
import sn.coophabitat.contribution.MemberObligationRepository;
import sn.coophabitat.document.DocumentCategory;
import sn.coophabitat.document.DocumentService;
import sn.coophabitat.document.DocumentVisibility;
import sn.coophabitat.document.StoredDocument;
import sn.coophabitat.member.Member;
import sn.coophabitat.member.MemberRepository;
import sn.coophabitat.payment.PaymentAllocation;
import sn.coophabitat.payment.PaymentAllocationRepository;
import sn.coophabitat.payment.PaymentIntent;
import sn.coophabitat.payment.PaymentIntentRepository;
import sn.coophabitat.payment.PaymentTransaction;
import sn.coophabitat.payment.PaymentTransactionRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class ReceiptService {

    private final ReceiptRepository receipts;
    private final ReceiptPdfGenerator generator;
    private final DocumentService documents;
    private final ReferenceGenerator references;
    private final MemberRepository members;
    private final PaymentIntentRepository intents;
    private final PaymentAllocationRepository allocations;
    private final PaymentTransactionRepository transactions;
    private final MemberObligationRepository obligations;
    private final CoopSettingsRepository settings;
    private final AppProperties props;
    private final Clock clock;

    public ReceiptService(ReceiptRepository receipts, ReceiptPdfGenerator generator, DocumentService documents,
                          ReferenceGenerator references, MemberRepository members, PaymentIntentRepository intents,
                          PaymentAllocationRepository allocations, PaymentTransactionRepository transactions,
                          MemberObligationRepository obligations, CoopSettingsRepository settings,
                          AppProperties props, Clock clock) {
        this.receipts = receipts;
        this.generator = generator;
        this.documents = documents;
        this.references = references;
        this.members = members;
        this.intents = intents;
        this.allocations = allocations;
        this.transactions = transactions;
        this.obligations = obligations;
        this.settings = settings;
        this.props = props;
        this.clock = clock;
    }

    /** Émet le reçu d'une transaction confirmée (une seule fois) et archive le PDF avec son empreinte. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Receipt issue(PaymentTransaction tx, BigDecimal outstandingAfter) {
        var existing = receipts.findByTransactionId(tx.getId());
        if (existing.isPresent()) {
            return existing.get();
        }
        Member member = members.findById(tx.getMemberId()).orElseThrow();
        PaymentIntent intent = intents.findById(tx.getIntentId()).orElseThrow();
        String number = references.nextReceiptNumber();
        String code = Hashing.randomCode(10);
        Instant now = Instant.now(clock);
        List<ReceiptPdfGenerator.Line> lines = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;
        for (PaymentAllocation a : allocations.findByTransactionIdOrderByIdAsc(tx.getId())) {
            String label = obligations.findById(a.getObligationId()).map(o -> o.getPeriodLabel()).orElse("Échéance");
            lines.add(new ReceiptPdfGenerator.Line(label, a.getAmount()));
            allocated = allocated.add(a.getAmount());
        }
        byte[] pdf = generator.generate(new ReceiptPdfGenerator.Data(settings.current(), number, code,
                props.frontendBaseUrl() + "/verification-recu", now, tx.getConfirmedAt(), member.fullName(),
                member.getMemberNumber(), tx.getAmount(), methodLabel(intent), tx.getProviderTransactionId(),
                tx.getReference(), "Confirmé", tx.getConfirmationSource(), lines, tx.getAmount().subtract(allocated),
                outstandingAfter, tx.isSimulated()));
        StoredDocument doc = documents.store(new DocumentService.NewDocument(
                (tx.isSimulated() ? "[SIMULATION] " : "") + "Reçu " + number, DocumentCategory.RECU,
                DocumentVisibility.PRIVE_MEMBRE, member.getId(), "PaymentTransaction", tx.getId(),
                number + ".pdf", "application/pdf", pdf, true, false));
        Receipt r = new Receipt();
        r.setReceiptNumber(number);
        r.setTransactionId(tx.getId());
        r.setMemberId(member.getId());
        r.setVerificationCode(code);
        r.setIssuedAt(now);
        r.setDocumentId(doc.getId());
        r.setSimulated(tx.isSimulated());
        return receipts.save(r);
    }

    public record Verification(boolean valid, String receiptNumber, Instant issuedAt, BigDecimal amount,
                               String transactionStatus, boolean simulated, String message) {
    }

    /** Vérification publique : ne révèle aucune donnée personnelle (ni nom, ni numéro d'adhérent). */
    @Transactional(readOnly = true)
    public Verification verify(String number, String code) {
        var r = receipts.findByReceiptNumber(number == null ? "" : number.trim().toUpperCase())
                .filter(x -> Hashing.constantTimeEquals(x.getVerificationCode(), code == null ? "" : code.trim().toUpperCase()));
        if (r.isEmpty()) {
            return new Verification(false, null, null, null, null, false,
                    "Aucun reçu ne correspond à ce numéro et à ce code");
        }
        Receipt receipt = r.get();
        PaymentTransaction t = transactions.findById(receipt.getTransactionId()).orElseThrow();
        String status = t.getStatus() == PaymentTransaction.Status.REMBOURSE ? "REMBOURSE" : "CONFIRME";
        return new Verification(true, receipt.getReceiptNumber(), receipt.getIssuedAt(), t.getAmount(), status,
                receipt.isSimulated(), receipt.isSimulated()
                ? "Reçu authentique d'un paiement SIMULÉ : aucun argent réel n'a été transféré"
                : status.equals("REMBOURSE") ? "Reçu authentique ; le paiement a depuis été remboursé"
                : "Reçu authentique ; paiement confirmé");
    }

    @Transactional(readOnly = true)
    public Receipt get(Long id) {
        return receipts.findById(id).orElseThrow(() -> BusinessException.notFound("Reçu"));
    }

    static String methodLabel(PaymentIntent intent) {
        return switch (intent.getProvider()) {
            case WAVE -> "Wave (mobile money)";
            case SENDFLASH -> "SendFlash";
            case MOCK -> "SIMULATION — aucun argent réel";
            case MANUEL -> switch (String.valueOf(intent.getChannel())) {
                case "ESPECES" -> "Espèces (encaissement vérifié)";
                case "CHEQUE" -> "Chèque" + (intent.getExternalReference() == null ? "" : " n° " + intent.getExternalReference());
                default -> "Virement bancaire" + (intent.getExternalReference() == null ? "" : " — réf. " + intent.getExternalReference());
            };
        };
    }
}
