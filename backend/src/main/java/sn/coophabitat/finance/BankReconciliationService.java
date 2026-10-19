package sn.coophabitat.finance;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.coophabitat.accounting.*;
import sn.coophabitat.audit.AuditCategory;
import sn.coophabitat.audit.AuditService;
import sn.coophabitat.common.BusinessException;
import sn.coophabitat.common.Hashing;
import sn.coophabitat.common.Money;
import sn.coophabitat.finance.FinanceDtos.*;
import sn.coophabitat.security.CurrentUser;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Soldes bancaires déclarés, import de relevés et rapprochement avec la comptabilité.
 * Un solde déclaré n'est jamais présenté comme un solde vérifié par la banque : la source est toujours indiquée.
 */
@Service
public class BankReconciliationService {

    private static final DateTimeFormatter FR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final int MAX_LINES = 5000;

    private final BankBalanceDeclarationRepository declarations;
    private final BankStatementLineRepository statementLines;
    private final FinancialAccountRepository accounts;
    private final AccountingLineRepository lines;
    private final AccountingEntryRepository entries;
    private final AuditService audit;
    private final Clock clock;

    public BankReconciliationService(BankBalanceDeclarationRepository declarations,
                                     BankStatementLineRepository statementLines, FinancialAccountRepository accounts,
                                     AccountingLineRepository lines, AccountingEntryRepository entries,
                                     AuditService audit, Clock clock) {
        this.declarations = declarations;
        this.statementLines = statementLines;
        this.accounts = accounts;
        this.lines = lines;
        this.entries = entries;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public BankBalanceDeclaration declare(DeclarationRequest r, BankBalanceDeclaration.Source source, CurrentUser user) {
        FinancialAccount account = realTreasury(r.accountCode());
        if (r.balanceDate().isAfter(LocalDate.now(clock))) {
            throw BusinessException.badRequest("DATE_FUTURE", "La date du solde ne peut pas être future");
        }
        if (declarations.existsByAccountCodeAndBalanceDateAndSource(account.getCode(), r.balanceDate(), source)) {
            throw BusinessException.conflict("SOLDE_DEJA_DECLARE", "Un solde est déjà enregistré pour ce compte à cette date");
        }
        BankBalanceDeclaration d = new BankBalanceDeclaration();
        d.setAccountCode(account.getCode());
        d.setBalanceDate(r.balanceDate());
        d.setBalance(Money.requireWholeXof(r.balance(), "Le solde"));
        d.setSource(source);
        d.setStatementReference(r.statementReference());
        d.setDeclaredBy(user.userId());
        d.setDeclaredAt(Instant.now(clock));
        d = declarations.save(d);
        audit.record(AuditCategory.FINANCE, "SOLDE_BANCAIRE_DECLARE", "BankBalanceDeclaration", d.getId(),
                Map.of("compte", d.getAccountCode(), "date", d.getBalanceDate().toString(), "solde", d.getBalance(),
                        "source", source.name()));
        return d;
    }

    /**
     * Import d'un relevé CSV (séparateur « ; » ou « , ») : date;libellé;montant;référence.
     * Montant positif = entrée de fonds, négatif = sortie. Les lignes déjà importées sont ignorées (empreinte).
     */
    @Transactional
    public ImportResult importStatement(String accountCode, byte[] csv, CurrentUser user) {
        FinancialAccount account = realTreasury(accountCode);
        String text = new String(csv, StandardCharsets.UTF_8).replace("﻿", "");
        String[] rows = text.split("\\r?\\n");
        if (rows.length > MAX_LINES) {
            throw BusinessException.badRequest("FICHIER_TROP_LONG", "Relevé limité à " + MAX_LINES + " lignes");
        }
        int imported = 0;
        int duplicates = 0;
        List<String> errors = new ArrayList<>();
        Map<String, Integer> occurrences = new HashMap<>();
        for (int i = 0; i < rows.length; i++) {
            String row = rows[i].trim();
            if (row.isEmpty() || (i == 0 && row.toLowerCase(Locale.ROOT).startsWith("date"))) {
                continue;
            }
            String[] cols = row.split(row.contains(";") ? ";" : ",", -1);
            if (cols.length < 3) {
                errors.add("Ligne " + (i + 1) + " : au moins trois colonnes attendues (date, libellé, montant)");
                continue;
            }
            LocalDate date;
            BigDecimal amount;
            try {
                date = parseDate(cols[0].trim());
                amount = new BigDecimal(cols[2].trim().replace(" ", "").replace(" ", "").replace(" ", ""));
                amount = Money.requireWholeXof(amount, "Le montant");
            } catch (DateTimeParseException | NumberFormatException | BusinessException e) {
                errors.add("Ligne " + (i + 1) + " : date ou montant illisible");
                continue;
            }
            String label = cols[1].trim();
            if (label.isEmpty() || amount.signum() == 0) {
                errors.add("Ligne " + (i + 1) + " : libellé vide ou montant nul");
                continue;
            }
            String reference = cols.length > 3 ? cols[3].trim() : null;
            String base = account.getCode() + "|" + date + "|" + label + "|" + amount.toPlainString() + "|" + reference;
            int occurrence = occurrences.merge(base, 1, Integer::sum);
            String hash = Hashing.sha256Hex(base + "|" + occurrence);
            if (statementLines.existsByImportHash(hash)) {
                duplicates++;
                continue;
            }
            BankStatementLine l = new BankStatementLine();
            l.setAccountCode(account.getCode());
            l.setOperationDate(date);
            l.setLabel(label.length() > 500 ? label.substring(0, 500) : label);
            l.setAmount(amount);
            l.setBankReference(reference == null || reference.isEmpty() ? null : reference);
            l.setImportedBy(user.userId());
            l.setImportedAt(Instant.now(clock));
            l.setImportHash(hash);
            statementLines.save(l);
            imported++;
        }
        audit.record(AuditCategory.FINANCE, "RELEVE_IMPORTE", "FinancialAccount", null,
                Map.of("compte", account.getCode(), "importees", imported, "doublons", duplicates, "erreurs", errors.size()));
        return new ImportResult(imported, duplicates, errors);
    }

    @Transactional
    public BankStatementLine match(Long lineId, Long entryId, CurrentUser user) {
        BankStatementLine l = statementLines.findById(lineId).orElseThrow(() -> BusinessException.notFound("Ligne de relevé"));
        if (l.getMatchedEntryId() != null) {
            throw BusinessException.conflict("DEJA_RAPPROCHEE", "Cette ligne est déjà rapprochée");
        }
        AccountingEntry e = entries.findById(entryId).orElseThrow(() -> BusinessException.notFound("Écriture"));
        if (e.isSimulated()) {
            throw BusinessException.rule("ECRITURE_SIMULEE", "Une écriture simulée ne peut pas être rapprochée d'un relevé réel");
        }
        if (statementLines.existsByMatchedEntryId(entryId)) {
            throw BusinessException.conflict("ECRITURE_DEJA_RAPPROCHEE", "Cette écriture est déjà rapprochée d'une autre ligne");
        }
        BigDecimal net = lines.findByEntryIdOrderByIdAsc(entryId).stream()
                .filter(x -> x.getAccountCode().equals(l.getAccountCode()))
                .map(x -> x.getDebit().subtract(x.getCredit())).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (net.compareTo(l.getAmount()) != 0) {
            throw BusinessException.rule("MONTANT_DIFFERENT", "L'écriture ne mouvemente pas le compte " + l.getAccountCode()
                    + " du même montant (" + Money.format(net) + " contre " + Money.format(l.getAmount()) + ")");
        }
        l.setMatchedEntryId(entryId);
        l.setMatchedBy(user.userId());
        l.setMatchedAt(Instant.now(clock));
        audit.record(AuditCategory.FINANCE, "RAPPROCHEMENT", "BankStatementLine", lineId,
                Map.of("ecriture", e.getReference(), "montant", l.getAmount()));
        return l;
    }

    @Transactional
    public BankStatementLine unmatch(Long lineId, String reason) {
        BankStatementLine l = statementLines.findById(lineId).orElseThrow(() -> BusinessException.notFound("Ligne de relevé"));
        if (l.getMatchedEntryId() == null) {
            throw BusinessException.rule("NON_RAPPROCHEE", "Cette ligne n'est pas rapprochée");
        }
        Long previous = l.getMatchedEntryId();
        l.setMatchedEntryId(null);
        l.setMatchedBy(null);
        l.setMatchedAt(null);
        audit.record(AuditCategory.FINANCE, "RAPPROCHEMENT_ANNULE", "BankStatementLine", lineId,
                Map.of("ecriture", previous, "motif", reason));
        return l;
    }

    @Transactional(readOnly = true)
    public List<StatementLineResponse> statement(String accountCode) {
        return statementLines.findByAccountCodeOrderByOperationDateDescIdDesc(accountCode).stream()
                .map(l -> new StatementLineResponse(l.getId(), l.getAccountCode(), l.getOperationDate(), l.getLabel(),
                        l.getAmount(), l.getBankReference(), l.getMatchedEntryId(),
                        l.getMatchedEntryId() == null ? null : entries.findById(l.getMatchedEntryId())
                                .map(AccountingEntry::getReference).orElse(null), l.getMatchedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DeclarationResponse> declarations(String accountCode) {
        return declarations.findByAccountCodeOrderByBalanceDateDescIdDesc(accountCode).stream()
                .map(BankReconciliationService::toResponse).toList();
    }

    /** État de rapprochement de chaque compte de trésorerie réel à une date donnée. */
    @Transactional(readOnly = true)
    public List<ReconciliationAccount> reconciliation(LocalDate asOf) {
        List<ReconciliationAccount> out = new ArrayList<>();
        for (FinancialAccount a : accounts.findAll().stream().filter(FinancialAccount::isTreasury)
                .sorted(Comparator.comparing(FinancialAccount::getCode)).toList()) {
            BigDecimal accounting = lines.balance(List.of(a.getCode()), asOf, false);
            Optional<BankBalanceDeclaration> d = declarations.findTopByAccountCodeOrderByBalanceDateDescIdDesc(a.getCode())
                    .filter(x -> !x.getBalanceDate().isAfter(asOf));
            BigDecimal gap = null;
            String status;
            if (d.isEmpty()) {
                status = "AUCUN_SOLDE_DECLARE";
            } else {
                BigDecimal atDeclaration = lines.balance(List.of(a.getCode()), d.get().getBalanceDate(), false);
                gap = d.get().getBalance().subtract(atDeclaration);
                status = gap.signum() == 0 ? "CONCORDANT" : "ECART";
            }
            out.add(new ReconciliationAccount(a.getCode(), a.getLabel(), accounting, d.map(BankBalanceDeclaration::getBalance).orElse(null),
                    d.map(BankBalanceDeclaration::getBalanceDate).orElse(null), d.map(BankBalanceDeclaration::getSource).orElse(null),
                    gap, statementLines.countByAccountCodeAndMatchedEntryIdIsNull(a.getCode()), status));
        }
        return out;
    }

    static DeclarationResponse toResponse(BankBalanceDeclaration d) {
        return new DeclarationResponse(d.getId(), d.getAccountCode(), d.getBalanceDate(), d.getBalance(), d.getSource(),
                d.getStatementReference(), d.getDeclaredBy(), d.getDeclaredAt());
    }

    private FinancialAccount realTreasury(String code) {
        FinancialAccount a = accounts.findById(code).orElseThrow(() -> BusinessException.notFound("Compte " + code));
        if (!a.isTreasury() || Accounts.SIMULATION.equals(a.getCode())) {
            throw BusinessException.rule("COMPTE_INVALIDE", "Seuls les comptes de trésorerie réels se rapprochent d'un relevé");
        }
        return a;
    }

    private static LocalDate parseDate(String s) {
        return s.contains("/") ? LocalDate.parse(s, FR) : LocalDate.parse(s);
    }
}
