package sn.coophabitat.finance;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import sn.coophabitat.accounting.FinancialAccount;
import sn.coophabitat.audit.AuditCategory;
import sn.coophabitat.audit.AuditService;
import sn.coophabitat.common.BusinessException;
import sn.coophabitat.common.Downloads;
import sn.coophabitat.common.PageResponse;
import sn.coophabitat.finance.FinanceDtos.*;
import sn.coophabitat.security.CurrentUserProvider;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/finance")
@Tag(name = "Finances, comptabilité et transparence")
public class FinanceController {

    private final ExpenseService expenses;
    private final BudgetService budgets;
    private final BankReconciliationService bank;
    private final TransparencyService transparency;
    private final LedgerService ledger;
    private final FinancialReportService reports;
    private final AuditService audit;

    public FinanceController(ExpenseService expenses, BudgetService budgets, BankReconciliationService bank,
                             TransparencyService transparency, LedgerService ledger, FinancialReportService reports,
                             AuditService audit) {
        this.expenses = expenses;
        this.budgets = budgets;
        this.bank = bank;
        this.transparency = transparency;
        this.ledger = ledger;
        this.reports = reports;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- transparence

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
    @Operation(summary = "Synthèse : soldes comptables, soldes déclarés, paiements confirmés, en attente et simulés")
    public TransparencySummary summary(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return transparency.summary(asOf);
    }

    // ---------------------------------------------------------------- dépenses

    @GetMapping("/expenses")
    @PreAuthorize("hasAuthority('ACCOUNTING_READ') or hasAuthority('EXPENSE_CREATE')")
    public PageResponse<ExpenseResponse> expenses(@RequestParam(required = false) Expense.Status status,
                                                  @RequestParam(required = false) ExpenseCategory category,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(expenses.search(status, category, from, to, page(page, size)), expenses::toResponse);
    }

    @GetMapping("/expenses/{id}")
    @PreAuthorize("hasAuthority('ACCOUNTING_READ') or hasAuthority('EXPENSE_CREATE')")
    public ExpenseResponse expense(@PathVariable Long id) {
        return expenses.toResponse(expenses.get(id));
    }

    @PostMapping("/expenses")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('EXPENSE_CREATE')")
    public ExpenseResponse createExpense(@Valid @RequestBody ExpenseRequest body) {
        return expenses.toResponse(expenses.create(body, CurrentUserProvider.require()));
    }

    @PutMapping("/expenses/{id}")
    @PreAuthorize("hasAuthority('EXPENSE_CREATE')")
    public ExpenseResponse updateExpense(@PathVariable Long id, @Valid @RequestBody ExpenseRequest body) {
        return expenses.toResponse(expenses.update(id, body, CurrentUserProvider.require()));
    }

    @PutMapping("/expenses/{id}/justification/{documentId}")
    @PreAuthorize("hasAuthority('EXPENSE_CREATE')")
    public ExpenseResponse attach(@PathVariable Long id, @PathVariable Long documentId) {
        return expenses.toResponse(expenses.attachJustification(id, documentId, CurrentUserProvider.require()));
    }

    @PostMapping("/expenses/{id}/submit")
    @PreAuthorize("hasAuthority('EXPENSE_CREATE')")
    public ExpenseResponse submit(@PathVariable Long id) {
        return expenses.toResponse(expenses.submit(id, CurrentUserProvider.require()));
    }

    @PostMapping("/expenses/{id}/decision")
    @PreAuthorize("hasAuthority('EXPENSE_APPROVE')")
    public ExpenseResponse decide(@PathVariable Long id, @Valid @RequestBody ExpenseDecisionRequest body) {
        return expenses.toResponse(expenses.decide(id, body, CurrentUserProvider.require()));
    }

    @PostMapping("/expenses/{id}/pay")
    @PreAuthorize("hasAuthority('EXPENSE_PAY')")
    public ExpenseResponse pay(@PathVariable Long id, @Valid @RequestBody ExpensePayRequest body) {
        return expenses.toResponse(expenses.pay(id, body, CurrentUserProvider.require()));
    }

    @PostMapping("/expenses/{id}/cancel")
    @PreAuthorize("hasAuthority('EXPENSE_CREATE') or hasAuthority('ACCOUNTING_WRITE')")
    public ExpenseResponse cancelExpense(@PathVariable Long id, @Valid @RequestBody ReasonRequest body) {
        return expenses.toResponse(expenses.cancel(id, body.reason(), CurrentUserProvider.require()));
    }

    // ---------------------------------------------------------------- budget

    @GetMapping("/budgets")
    @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
    public List<BudgetResponse> budgets() {
        return budgets.list().stream().map(budgets::execution).toList();
    }

    @GetMapping("/budgets/{id}")
    @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
    public BudgetResponse budget(@PathVariable Long id) {
        return budgets.execution(budgets.get(id));
    }

    @PostMapping("/budgets")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('BUDGET_WRITE')")
    public BudgetResponse createBudget(@Valid @RequestBody BudgetRequest body) {
        return budgets.execution(budgets.create(body, CurrentUserProvider.require()));
    }

    @PutMapping("/budgets/{id}")
    @PreAuthorize("hasAuthority('BUDGET_WRITE')")
    public BudgetResponse updateBudget(@PathVariable Long id, @Valid @RequestBody BudgetRequest body) {
        return budgets.execution(budgets.update(id, body));
    }

    @PostMapping("/budgets/{id}/adopt")
    @PreAuthorize("hasAuthority('BUDGET_WRITE')")
    @Operation(summary = "Marque le budget comme adopté (référence de la décision d'assemblée obligatoire)")
    public BudgetResponse adopt(@PathVariable Long id, @Valid @RequestBody AdoptRequest body) {
        return budgets.execution(budgets.adopt(id, body.adoptedReference()));
    }

    @PostMapping("/budgets/{id}/close")
    @PreAuthorize("hasAuthority('BUDGET_WRITE')")
    public BudgetResponse closeBudget(@PathVariable Long id) {
        return budgets.execution(budgets.close(id));
    }

    // ---------------------------------------------------------------- comptabilité

    @GetMapping("/accounts")
    @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
    public List<FinancialAccount> chart() {
        return ledger.chart();
    }

    @GetMapping("/entries")
    @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
    public PageResponse<EntryResponse> entries(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                               @RequestParam(required = false) Boolean simulated,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(ledger.search(from, to, simulated, page(page, size)), e -> e);
    }

    @GetMapping("/entries/{id}")
    @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
    public EntryResponse entry(@PathVariable Long id) {
        return ledger.get(id);
    }

    @PostMapping("/entries")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ACCOUNTING_WRITE')")
    public EntryResponse manualEntry(@Valid @RequestBody ManualEntryRequest body) {
        return ledger.get(ledger.manualEntry(body).getId());
    }

    @PostMapping("/entries/{id}/reverse")
    @PreAuthorize("hasAuthority('ACCOUNTING_WRITE')")
    @Operation(summary = "Contre-passe une écriture manuelle (l'écriture d'origine reste visible)")
    public EntryResponse reverse(@PathVariable Long id, @Valid @RequestBody ReasonRequest body) {
        return ledger.get(ledger.reverse(id, body.reason()).getId());
    }

    @GetMapping("/trial-balance")
    @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
    public List<AccountBalance> trialBalance(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                             @RequestParam(defaultValue = "false") boolean simulated) {
        return ledger.trialBalance(from, to, simulated);
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
    public ResponseEntity<byte[]> export(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (to.isBefore(from)) {
            throw BusinessException.badRequest("PERIODE_INVALIDE", "Période invalide");
        }
        audit.record(AuditCategory.FINANCE, "EXPORT_COMPTABLE", "AccountingEntry", null, Map.of("du", from.toString(), "au", to.toString()));
        return Downloads.file(ledger.exportExcel(from, to), "comptabilite-" + from + "-" + to + ".xlsx", Downloads.XLSX, false);
    }

    // ---------------------------------------------------------------- banque

    @GetMapping("/bank/reconciliation")
    @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
    public List<ReconciliationAccount> reconciliation(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return bank.reconciliation(asOf == null ? LocalDate.now(sn.coophabitat.config.ClockConfig.DAKAR) : asOf);
    }

    @GetMapping("/bank/{accountCode}/declarations")
    @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
    public List<DeclarationResponse> declarations(@PathVariable String accountCode) {
        return bank.declarations(accountCode);
    }

    @PostMapping("/bank/declarations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('BANK_RECONCILE')")
    public DeclarationResponse declare(@Valid @RequestBody DeclarationRequest body) {
        return BankReconciliationService.toResponse(bank.declare(body, BankBalanceDeclaration.Source.DECLARATION,
                CurrentUserProvider.require()));
    }

    @GetMapping("/bank/{accountCode}/statement")
    @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
    public List<StatementLineResponse> statement(@PathVariable String accountCode) {
        return bank.statement(accountCode);
    }

    @PostMapping(value = "/bank/{accountCode}/statement", consumes = "multipart/form-data")
    @PreAuthorize("hasAuthority('BANK_RECONCILE')")
    @Operation(summary = "Importe un relevé CSV : date;libellé;montant;référence")
    public ImportResult importStatement(@PathVariable String accountCode, @RequestParam("file") MultipartFile file)
            throws IOException {
        if (file.getSize() > 2_000_000) {
            throw BusinessException.badRequest("FICHIER_TROP_GROS", "Relevé limité à 2 Mo");
        }
        return bank.importStatement(accountCode, file.getBytes(), CurrentUserProvider.require());
    }

    @PostMapping("/bank/statement-lines/{lineId}/match")
    @PreAuthorize("hasAuthority('BANK_RECONCILE')")
    public StatementLineResponse match(@PathVariable Long lineId, @Valid @RequestBody MatchRequest body) {
        BankStatementLine l = bank.match(lineId, body.entryId(), CurrentUserProvider.require());
        return bank.statement(l.getAccountCode()).stream().filter(x -> x.id().equals(lineId)).findFirst().orElseThrow();
    }

    @PostMapping("/bank/statement-lines/{lineId}/unmatch")
    @PreAuthorize("hasAuthority('BANK_RECONCILE')")
    public StatementLineResponse unmatch(@PathVariable Long lineId, @Valid @RequestBody ReasonRequest body) {
        BankStatementLine l = bank.unmatch(lineId, body.reason());
        return bank.statement(l.getAccountCode()).stream().filter(x -> x.id().equals(lineId)).findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------- rapports

    @GetMapping("/reports")
    public List<ReportResponse> reports() {
        boolean all = CurrentUserProvider.require().has(sn.coophabitat.security.Permission.ACCOUNTING_READ);
        return reports.list(!all).stream().map(r -> reports.toResponse(r, false)).toList();
    }

    @GetMapping("/reports/{id}")
    public ReportResponse report(@PathVariable Long id) {
        FinancialReport r = reports.get(id);
        boolean all = CurrentUserProvider.require().has(sn.coophabitat.security.Permission.ACCOUNTING_READ);
        if (!all && r.getStatus() != FinancialReport.Status.PUBLIE) {
            throw BusinessException.notFound("Rapport");
        }
        return reports.toResponse(r, true);
    }

    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('REPORT_GENERATE')")
    public ReportResponse generate(@Valid @RequestBody ReportRequest body) {
        return reports.toResponse(reports.generate(body, CurrentUserProvider.require()), true);
    }

    @PostMapping("/reports/{id}/publish")
    @PreAuthorize("hasAuthority('REPORT_PUBLISH')")
    public ReportResponse publish(@PathVariable Long id) {
        return reports.toResponse(reports.publish(id, CurrentUserProvider.require()), false);
    }

    private static PageRequest page(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }
}
