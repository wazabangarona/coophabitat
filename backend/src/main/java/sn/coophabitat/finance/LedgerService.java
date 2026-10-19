package sn.coophabitat.finance;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.coophabitat.accounting.*;
import sn.coophabitat.audit.AuditCategory;
import sn.coophabitat.audit.AuditService;
import sn.coophabitat.common.BusinessException;
import sn.coophabitat.finance.FinanceDtos.*;
import sn.coophabitat.reporting.ExcelExporter;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;

/** Consultation du grand livre, balance des comptes et écritures manuelles motivées. */
@Service
public class LedgerService {

    /** Comptes réservés aux flux automatiques : aucune écriture manuelle ne peut les mouvementer. */
    private static final Set<String> AUTOMATIC_ONLY = Set.of(Accounts.SIMULATION);

    private final AccountingService accounting;
    private final AccountingEntryRepository entries;
    private final AccountingLineRepository lines;
    private final FinancialAccountRepository accounts;
    private final AuditService audit;
    private final Clock clock;

    public LedgerService(AccountingService accounting, AccountingEntryRepository entries, AccountingLineRepository lines,
                         FinancialAccountRepository accounts, AuditService audit, Clock clock) {
        this.accounting = accounting;
        this.entries = entries;
        this.lines = lines;
        this.accounts = accounts;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public AccountingEntry manualEntry(ManualEntryRequest r) {
        if (r.entryDate().isAfter(LocalDate.now(clock))) {
            throw BusinessException.badRequest("DATE_FUTURE", "La date d'une écriture ne peut pas être future");
        }
        List<AccountingService.LineSpec> specs = new ArrayList<>();
        for (ManualLine l : r.lines()) {
            if (AUTOMATIC_ONLY.contains(l.accountCode())) {
                throw BusinessException.rule("COMPTE_RESERVE", "Le compte " + l.accountCode() + " est réservé aux flux automatiques");
            }
            specs.add(new AccountingService.LineSpec(l.accountCode(), l.debit(), l.credit(), l.memberId()));
        }
        AccountingEntry e = accounting.post(r.entryDate(), r.label(), "MANUELLE", null, false, specs);
        audit.record(AuditCategory.FINANCE, "ECRITURE_MANUELLE", "AccountingEntry", e.getId(),
                Map.of("reference", e.getReference(), "justification", r.justification()));
        return e;
    }

    @Transactional
    public AccountingEntry reverse(Long id, String reason) {
        AccountingEntry original = entries.findById(id).orElseThrow(() -> BusinessException.notFound("Écriture"));
        if (!"MANUELLE".equals(original.getSourceType())) {
            throw BusinessException.rule("ECRITURE_AUTOMATIQUE",
                    "Les écritures issues d'un paiement ou d'une dépense se corrigent par le remboursement ou l'annulation correspondants");
        }
        return accounting.reverse(id, reason);
    }

    @Transactional(readOnly = true)
    public Page<EntryResponse> search(LocalDate from, LocalDate to, Boolean simulated, Pageable p) {
        Page<AccountingEntry> page = entries.search(from, to, simulated, p);
        Map<Long, List<AccountingLine>> byEntry = new HashMap<>();
        if (!page.isEmpty()) {
            for (AccountingLine l : lines.findByEntryIdInOrderByIdAsc(page.map(AccountingEntry::getId).getContent())) {
                byEntry.computeIfAbsent(l.getEntryId(), k -> new ArrayList<>()).add(l);
            }
        }
        Map<String, String> labels = accountLabels();
        return page.map(e -> toResponse(e, byEntry.getOrDefault(e.getId(), List.of()), labels));
    }

    @Transactional(readOnly = true)
    public EntryResponse get(Long id) {
        AccountingEntry e = entries.findById(id).orElseThrow(() -> BusinessException.notFound("Écriture"));
        return toResponse(e, lines.findByEntryIdOrderByIdAsc(id), accountLabels());
    }

    /** Balance des comptes sur une période ; les écritures simulées sont présentées à part. */
    @Transactional(readOnly = true)
    public List<AccountBalance> trialBalance(LocalDate from, LocalDate to, boolean simulated) {
        Map<String, FinancialAccount> all = new TreeMap<>();
        accounts.findAll().forEach(a -> all.put(a.getCode(), a));
        List<AccountBalance> out = new ArrayList<>();
        for (Object[] row : lines.movementsByAccount(from, to, simulated)) {
            FinancialAccount a = all.get((String) row[0]);
            BigDecimal d = (BigDecimal) row[1];
            BigDecimal c = (BigDecimal) row[2];
            out.add(new AccountBalance(a.getCode(), a.getLabel(), a.getAccountType(), a.isTreasury(), d, c, d.subtract(c)));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<FinancialAccount> chart() {
        return accounts.findAll().stream().sorted(Comparator.comparing(FinancialAccount::getCode)).toList();
    }

    @Transactional(readOnly = true)
    public byte[] exportExcel(LocalDate from, LocalDate to) {
        List<List<Object>> journal = new ArrayList<>();
        int page = 0;
        Page<EntryResponse> p;
        do {
            p = search(from, to, null, org.springframework.data.domain.PageRequest.of(page++, 500));
            for (EntryResponse e : p.getContent()) {
                for (LineResponse l : e.lines()) {
                    journal.add(List.of(e.entryDate(), e.reference(), e.label(), l.accountCode(), l.accountLabel(),
                            l.debit(), l.credit(), e.simulated() ? "SIMULATION" : "RÉEL",
                            e.reversalOfId() == null ? "" : "Contre-écriture"));
                }
            }
        } while (p.hasNext());
        List<List<Object>> balance = new ArrayList<>();
        for (AccountBalance b : trialBalance(from, to, false)) {
            balance.add(List.of(b.code(), b.label(), b.debit(), b.credit(), b.balance()));
        }
        String period = "Période du " + from + " au " + to;
        return ExcelExporter.export(List.of(
                new ExcelExporter.Sheet("Journal", List.of(period,
                        "Les lignes marquées SIMULATION ne correspondent à aucun argent réel."),
                        List.of("Date", "Référence", "Libellé", "Compte", "Intitulé", "Débit (FCFA)", "Crédit (FCFA)", "Nature", "Type"),
                        journal),
                new ExcelExporter.Sheet("Balance", List.of(period, "Écritures réelles uniquement (hors simulation)."),
                        List.of("Compte", "Intitulé", "Débit (FCFA)", "Crédit (FCFA)", "Solde (FCFA)"), balance)));
    }

    private Map<String, String> accountLabels() {
        Map<String, String> m = new HashMap<>();
        accounts.findAll().forEach(a -> m.put(a.getCode(), a.getLabel()));
        return m;
    }

    private EntryResponse toResponse(AccountingEntry e, List<AccountingLine> ls, Map<String, String> labels) {
        Long reversedBy = entries.findByReversalOfId(e.getId()).map(AccountingEntry::getId).orElse(null);
        return new EntryResponse(e.getId(), e.getReference(), e.getEntryDate(), e.getLabel(), e.getSourceType(),
                e.getSourceId(), e.getReversalOfId(), reversedBy, e.isSimulated(), e.getCreatedAt(),
                ls.stream().map(l -> new LineResponse(l.getAccountCode(), labels.get(l.getAccountCode()), l.getDebit(),
                        l.getCredit(), l.getMemberId())).toList());
    }
}
