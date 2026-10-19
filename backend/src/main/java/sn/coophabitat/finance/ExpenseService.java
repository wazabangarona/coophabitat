package sn.coophabitat.finance;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.coophabitat.accounting.*;
import sn.coophabitat.audit.AuditCategory;
import sn.coophabitat.audit.AuditService;
import sn.coophabitat.common.BusinessException;
import sn.coophabitat.common.Money;
import sn.coophabitat.common.ReferenceGenerator;
import sn.coophabitat.config.AppProperties;
import sn.coophabitat.document.DocumentService;
import sn.coophabitat.finance.FinanceDtos.*;
import sn.coophabitat.security.CurrentUser;
import sn.coophabitat.security.UserAccount;
import sn.coophabitat.security.UserAccountRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Dépenses avec séparation des tâches :
 * le saisissant ne peut pas approuver sa dépense, l'approbateur ne peut pas la payer,
 * et au-delà du seuil configuré deux approbations distinctes sont exigées.
 * Le paiement produit l'écriture comptable ; une dépense payée ne se corrige que par contre-écriture.
 */
@Service
public class ExpenseService {

    private final ExpenseRepository expenses;
    private final ExpenseApprovalRepository approvals;
    private final AccountingService accounting;
    private final AccountingLineRepository lines;
    private final FinancialAccountRepository accounts;
    private final DocumentService documents;
    private final UserAccountRepository users;
    private final ReferenceGenerator references;
    private final AuditService audit;
    private final AppProperties props;
    private final Clock clock;

    public ExpenseService(ExpenseRepository expenses, ExpenseApprovalRepository approvals, AccountingService accounting,
                          AccountingLineRepository lines, FinancialAccountRepository accounts, DocumentService documents,
                          UserAccountRepository users, ReferenceGenerator references, AuditService audit,
                          AppProperties props, Clock clock) {
        this.expenses = expenses;
        this.approvals = approvals;
        this.accounting = accounting;
        this.lines = lines;
        this.accounts = accounts;
        this.documents = documents;
        this.users = users;
        this.references = references;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public Expense create(ExpenseRequest r, CurrentUser user) {
        Expense e = new Expense();
        e.setReference(references.next("DEP"));
        e.setCreatedBy(user.userId());
        fill(e, r);
        e = expenses.save(e);
        audit.record(AuditCategory.FINANCE, "DEPENSE_SAISIE", "Expense", e.getId(),
                Map.of("reference", e.getReference(), "montant", e.getAmount(), "categorie", e.getCategory().name()));
        return e;
    }

    @Transactional
    public Expense update(Long id, ExpenseRequest r, CurrentUser user) {
        Expense e = lock(id);
        if (e.getStatus() != Expense.Status.BROUILLON) {
            throw BusinessException.rule("DEPENSE_FIGEE", "Seule une dépense en brouillon est modifiable");
        }
        if (!e.getCreatedBy().equals(user.userId())) {
            throw BusinessException.forbidden("Seul l'auteur de la saisie peut la modifier");
        }
        BigDecimal before = e.getAmount();
        fill(e, r);
        audit.record(AuditCategory.FINANCE, "DEPENSE_MODIFIEE", "Expense", id,
                Map.of("montant_avant", before, "montant_apres", e.getAmount()));
        return e;
    }

    @Transactional
    public Expense submit(Long id, CurrentUser user) {
        Expense e = lock(id);
        if (e.getStatus() != Expense.Status.BROUILLON) {
            throw BusinessException.rule("ETAPE_INVALIDE", "Dépense déjà soumise");
        }
        if (e.getJustificationDocumentId() == null) {
            throw BusinessException.rule("JUSTIFICATIF_REQUIS", "Joignez une pièce justificative avant de soumettre la dépense");
        }
        e.setRequiredApprovals(e.getAmount().compareTo(props.accounting().doubleApprovalThreshold()) >= 0 ? 2 : 1);
        e.setStatus(Expense.Status.SOUMISE);
        e.setSubmittedAt(Instant.now(clock));
        audit.record(AuditCategory.FINANCE, "DEPENSE_SOUMISE", "Expense", id,
                Map.of("approbations_requises", e.getRequiredApprovals()));
        return e;
    }

    @Transactional
    public Expense decide(Long id, ExpenseDecisionRequest r, CurrentUser user) {
        Expense e = lock(id);
        if (e.getStatus() != Expense.Status.SOUMISE) {
            throw BusinessException.rule("ETAPE_INVALIDE", "La dépense n'est pas en attente d'approbation");
        }
        if (e.getCreatedBy().equals(user.userId())) {
            throw BusinessException.forbidden("Séparation des tâches : vous ne pouvez pas approuver une dépense que vous avez saisie");
        }
        if (approvals.existsByExpenseIdAndApproverId(id, user.userId())) {
            throw BusinessException.conflict("DEJA_DECIDE", "Vous vous êtes déjà prononcé sur cette dépense");
        }
        if (r.decision() == ExpenseApproval.Decision.REJETEE && (r.comment() == null || r.comment().isBlank())) {
            throw BusinessException.badRequest("MOTIF_REQUIS", "Le motif du rejet est obligatoire");
        }
        ExpenseApproval a = new ExpenseApproval();
        a.setExpenseId(id);
        a.setApproverId(user.userId());
        a.setDecision(r.decision());
        a.setComment(r.comment());
        a.setDecidedAt(Instant.now(clock));
        approvals.saveAndFlush(a);
        if (r.decision() == ExpenseApproval.Decision.REJETEE) {
            e.setStatus(Expense.Status.REJETEE);
        } else if (approvedCount(id) >= e.getRequiredApprovals()) {
            e.setStatus(Expense.Status.APPROUVEE);
        }
        audit.record(AuditCategory.FINANCE, r.decision() == ExpenseApproval.Decision.REJETEE ? "DEPENSE_REJETEE" : "DEPENSE_APPROBATION",
                "Expense", id, Map.of("statut", e.getStatus().name(), "commentaire", String.valueOf(r.comment())));
        return e;
    }

    @Transactional
    public Expense pay(Long id, ExpensePayRequest r, CurrentUser user) {
        Expense e = lock(id);
        if (e.getStatus() != Expense.Status.APPROUVEE) {
            throw BusinessException.rule("NON_APPROUVEE", "Seule une dépense approuvée peut être payée");
        }
        if (approvals.findByExpenseIdOrderByIdAsc(id).stream().anyMatch(a -> a.getApproverId().equals(user.userId()))) {
            throw BusinessException.forbidden("Séparation des tâches : l'approbateur ne peut pas enregistrer le paiement");
        }
        FinancialAccount account = accounts.findById(r.paymentAccount())
                .orElseThrow(() -> BusinessException.badRequest("COMPTE_INCONNU", "Compte de paiement inconnu"));
        if (!account.isTreasury() || Accounts.SIMULATION.equals(account.getCode())) {
            throw BusinessException.rule("COMPTE_INVALIDE", "Le paiement doit sortir d'un compte de trésorerie réel");
        }
        LocalDate date = r.paymentDate() == null ? LocalDate.now(clock) : r.paymentDate();
        if (date.isAfter(LocalDate.now(clock))) {
            throw BusinessException.badRequest("DATE_FUTURE", "La date de paiement ne peut pas être future");
        }
        BigDecimal available = lines.balance(List.of(account.getCode()), date, false);
        if (available.compareTo(e.getAmount()) < 0) {
            throw BusinessException.rule("SOLDE_INSUFFISANT", "Solde comptable du compte " + account.getCode()
                    + " insuffisant (" + Money.format(available) + ") pour cette dépense");
        }
        AccountingEntry entry = accounting.post(date, "Dépense " + e.getReference() + " — " + e.getLabel(), "EXPENSE", id,
                false, List.of(AccountingService.LineSpec.debit(e.getCategory().accountCode(), e.getAmount(), null),
                        AccountingService.LineSpec.credit(account.getCode(), e.getAmount(), null)));
        e.setStatus(Expense.Status.PAYEE);
        e.setPaymentAccount(account.getCode());
        e.setPaidAt(Instant.now(clock));
        e.setPaidBy(user.userId());
        e.setAccountingEntryId(entry.getId());
        audit.record(AuditCategory.FINANCE, "DEPENSE_PAYEE", "Expense", id,
                Map.of("compte", account.getCode(), "ecriture", entry.getReference(), "montant", e.getAmount()));
        return e;
    }

    /** Annulation : avant paiement, simple changement d'état motivé ; après paiement, contre-écriture. */
    @Transactional
    public Expense cancel(Long id, String reason, CurrentUser user) {
        Expense e = lock(id);
        if (e.getStatus() == Expense.Status.ANNULEE || e.getStatus() == Expense.Status.REJETEE) {
            throw BusinessException.rule("ETAPE_INVALIDE", "Dépense déjà close");
        }
        if (e.getStatus() == Expense.Status.PAYEE) {
            if (!user.has(sn.coophabitat.security.Permission.ACCOUNTING_WRITE)) {
                throw BusinessException.forbidden("L'annulation d'une dépense payée exige le droit de contre-passation");
            }
            AccountingEntry rev = accounting.reverse(e.getAccountingEntryId(), "Annulation de la dépense " + e.getReference() + " : " + reason);
            audit.record(AuditCategory.FINANCE, "DEPENSE_CONTRE_PASSEE", "Expense", id,
                    Map.of("contre_ecriture", rev.getReference(), "motif", reason));
        } else {
            audit.record(AuditCategory.FINANCE, "DEPENSE_ANNULEE", "Expense", id, Map.of("motif", reason));
        }
        e.setStatus(Expense.Status.ANNULEE);
        e.setCancelReason(reason);
        return e;
    }

    @Transactional
    public Expense attachJustification(Long id, Long documentId, CurrentUser user) {
        Expense e = lock(id);
        if (e.getStatus() != Expense.Status.BROUILLON && e.getStatus() != Expense.Status.SOUMISE) {
            throw BusinessException.rule("DEPENSE_FIGEE", "Justificatif modifiable uniquement avant approbation");
        }
        documents.get(documentId);
        e.setJustificationDocumentId(documentId);
        audit.record(AuditCategory.FINANCE, "DEPENSE_JUSTIFICATIF", "Expense", id, Map.of("document", documentId));
        return e;
    }

    @Transactional(readOnly = true)
    public Page<Expense> search(Expense.Status status, ExpenseCategory category, LocalDate from, LocalDate to, Pageable p) {
        return expenses.search(status, category, from, to, p);
    }

    @Transactional(readOnly = true)
    public Expense get(Long id) {
        return expenses.findById(id).orElseThrow(() -> BusinessException.notFound("Dépense"));
    }

    public ExpenseResponse toResponse(Expense e) {
        List<ApprovalItem> items = approvals.findByExpenseIdOrderByIdAsc(e.getId()).stream()
                .map(a -> new ApprovalItem(a.getApproverId(),
                        users.findById(a.getApproverId()).map(UserAccount::getDisplayName).orElse(null),
                        a.getDecision(), a.getComment(), a.getDecidedAt()))
                .toList();
        long approved = items.stream().filter(i -> i.decision() == ExpenseApproval.Decision.APPROUVEE).count();
        return new ExpenseResponse(e.getId(), e.getReference(), e.getCategory(), e.getCategory().label(), e.getLabel(),
                e.getSupplier(), e.getAmount(), e.getExpenseDate(), e.getStatus(), e.getPaymentAccount(),
                e.getJustificationDocumentId(), e.getRequiredApprovals(), approved, e.getCreatedBy(), e.getCreatedAt(),
                e.getSubmittedAt(), e.getPaidAt(), e.getPaidBy(), e.getAccountingEntryId(), e.getCancelReason(), e.isDemo(),
                items);
    }

    public BigDecimal sum(EnumSet<Expense.Status> statuses) {
        return expenses.sumByStatuses(statuses);
    }

    private long approvedCount(Long id) {
        return approvals.findByExpenseIdOrderByIdAsc(id).stream()
                .filter(a -> a.getDecision() == ExpenseApproval.Decision.APPROUVEE).count();
    }

    private void fill(Expense e, ExpenseRequest r) {
        if (r.expenseDate().isAfter(LocalDate.now(clock))) {
            throw BusinessException.badRequest("DATE_FUTURE", "La date de la dépense ne peut pas être future");
        }
        if (r.justificationDocumentId() != null) {
            documents.get(r.justificationDocumentId());
        }
        e.setCategory(r.category());
        e.setLabel(r.label().trim());
        e.setSupplier(r.supplier());
        e.setAmount(Money.requirePositiveXof(r.amount(), "Le montant"));
        e.setExpenseDate(r.expenseDate());
        e.setJustificationDocumentId(r.justificationDocumentId());
    }

    private Expense lock(Long id) {
        return expenses.lockById(id).orElseThrow(() -> BusinessException.notFound("Dépense"));
    }
}
