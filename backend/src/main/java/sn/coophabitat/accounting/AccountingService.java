package sn.coophabitat.accounting;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.coophabitat.audit.AuditCategory;
import sn.coophabitat.audit.AuditService;
import sn.coophabitat.common.BusinessException;
import sn.coophabitat.common.ReferenceGenerator;
import sn.coophabitat.security.CurrentUser;
import sn.coophabitat.security.CurrentUserProvider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Comptabilité en partie double. Les écritures sont immuables : toute correction passe par
 * {@link #reverse(Long, String)}, qui produit une contre-écriture liée à l'écriture d'origine.
 */
@Service
public class AccountingService {

    private final AccountingEntryRepository entries;
    private final AccountingLineRepository lines;
    private final FinancialAccountRepository accounts;
    private final ReferenceGenerator references;
    private final AuditService audit;

    public AccountingService(AccountingEntryRepository entries, AccountingLineRepository lines,
                             FinancialAccountRepository accounts, ReferenceGenerator references, AuditService audit) {
        this.entries = entries;
        this.lines = lines;
        this.accounts = accounts;
        this.references = references;
        this.audit = audit;
    }

    public record LineSpec(String accountCode, BigDecimal debit, BigDecimal credit, Long memberId) {
        public static LineSpec debit(String account, BigDecimal amount, Long memberId) {
            return new LineSpec(account, amount, BigDecimal.ZERO, memberId);
        }

        public static LineSpec credit(String account, BigDecimal amount, Long memberId) {
            return new LineSpec(account, BigDecimal.ZERO, amount, memberId);
        }
    }

    @Transactional
    public AccountingEntry post(LocalDate date, String label, String sourceType, Long sourceId, boolean simulated,
                                List<LineSpec> specs) {
        List<LineSpec> effective = specs.stream()
                .filter(s -> s.debit().signum() != 0 || s.credit().signum() != 0).toList();
        validate(effective);
        AccountingEntry entry = new AccountingEntry();
        entry.setReference(references.nextEntryReference());
        entry.setEntryDate(date);
        entry.setLabel(label);
        entry.setSourceType(sourceType);
        entry.setSourceId(sourceId);
        entry.setSimulated(simulated);
        entry.setCreatedBy(CurrentUserProvider.current().map(CurrentUser::userId).orElse(null));
        entry = entries.save(entry);
        for (LineSpec s : effective) {
            AccountingLine line = new AccountingLine();
            line.setEntryId(entry.getId());
            line.setAccountCode(s.accountCode());
            line.setDebit(s.debit());
            line.setCredit(s.credit());
            line.setMemberId(s.memberId());
            lines.save(line);
        }
        audit.record(AuditCategory.FINANCE, "ECRITURE_PASSEE", "AccountingEntry", entry.getId(),
                Map.of("reference", entry.getReference(), "source", sourceType, "simule", simulated,
                        "montant", effective.stream().map(LineSpec::debit).reduce(BigDecimal.ZERO, BigDecimal::add)));
        return entry;
    }

    /** Contre-passation intégrale d'une écriture (débit et crédit inversés). Une écriture n'est contre-passée qu'une fois. */
    @Transactional
    public AccountingEntry reverse(Long entryId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw BusinessException.badRequest("MOTIF_REQUIS", "Le motif de la contre-écriture est obligatoire");
        }
        AccountingEntry original = entries.findById(entryId).orElseThrow(() -> BusinessException.notFound("Écriture"));
        if (original.getReversalOfId() != null) {
            throw BusinessException.rule("CONTRE_ECRITURE", "Une contre-écriture ne peut pas être elle-même contre-passée");
        }
        if (entries.findByReversalOfId(entryId).isPresent()) {
            throw BusinessException.conflict("DEJA_CONTRE_PASSEE", "Cette écriture a déjà été contre-passée");
        }
        List<LineSpec> inverted = new ArrayList<>();
        for (AccountingLine l : lines.findByEntryIdOrderByIdAsc(entryId)) {
            inverted.add(new LineSpec(l.getAccountCode(), l.getCredit(), l.getDebit(), l.getMemberId()));
        }
        validate(inverted);
        AccountingEntry reversal = new AccountingEntry();
        reversal.setReference(references.nextEntryReference());
        reversal.setEntryDate(LocalDate.now(sn.coophabitat.config.ClockConfig.DAKAR));
        reversal.setLabel("Contre-écriture de " + original.getReference() + " — " + reason);
        reversal.setSourceType(original.getSourceType());
        reversal.setSourceId(original.getSourceId());
        reversal.setReversalOfId(original.getId());
        reversal.setSimulated(original.isSimulated());
        reversal.setCreatedBy(CurrentUserProvider.current().map(CurrentUser::userId).orElse(null));
        reversal = entries.save(reversal);
        for (LineSpec s : inverted) {
            AccountingLine line = new AccountingLine();
            line.setEntryId(reversal.getId());
            line.setAccountCode(s.accountCode());
            line.setDebit(s.debit());
            line.setCredit(s.credit());
            line.setMemberId(s.memberId());
            lines.save(line);
        }
        audit.record(AuditCategory.FINANCE, "CONTRE_ECRITURE", "AccountingEntry", reversal.getId(),
                Map.of("origine", original.getReference(), "reference", reversal.getReference(), "motif", reason));
        return reversal;
    }

    private void validate(List<LineSpec> specs) {
        if (specs.size() < 2) {
            throw BusinessException.rule("ECRITURE_INCOMPLETE", "Une écriture comporte au moins deux lignes");
        }
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (LineSpec s : specs) {
            if (s.debit().signum() < 0 || s.credit().signum() < 0
                    || (s.debit().signum() > 0 && s.credit().signum() > 0)) {
                throw BusinessException.rule("LIGNE_INVALIDE", "Chaque ligne porte soit un débit, soit un crédit positif");
            }
            if (s.debit().scale() > 0 && s.debit().stripTrailingZeros().scale() > 0
                    || s.credit().scale() > 0 && s.credit().stripTrailingZeros().scale() > 0) {
                throw BusinessException.rule("MONTANT_INVALIDE", "Les montants en XOF sont des entiers");
            }
            if (!accounts.existsById(s.accountCode())) {
                throw BusinessException.rule("COMPTE_INCONNU", "Compte inconnu : " + s.accountCode());
            }
            debit = debit.add(s.debit());
            credit = credit.add(s.credit());
        }
        if (debit.compareTo(credit) != 0) {
            throw BusinessException.rule("ECRITURE_DESEQUILIBREE", "Le total des débits doit égaler le total des crédits");
        }
    }
}
