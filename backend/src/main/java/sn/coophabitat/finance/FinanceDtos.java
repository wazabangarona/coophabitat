package sn.coophabitat.finance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class FinanceDtos {

    private FinanceDtos() {
    }

    // ------------------------------------------------------------ dépenses

    public record ExpenseRequest(@NotNull ExpenseCategory category, @NotBlank @Size(max = 300) String label,
                                 @Size(max = 200) String supplier, @NotNull @Positive BigDecimal amount,
                                 @NotNull LocalDate expenseDate, Long justificationDocumentId) {
    }

    public record ExpenseDecisionRequest(@NotNull ExpenseApproval.Decision decision, @Size(max = 1000) String comment) {
    }

    public record ExpensePayRequest(@NotBlank String paymentAccount, LocalDate paymentDate) {
    }

    public record ReasonRequest(@NotBlank @Size(min = 10, max = 1000) String reason) {
    }

    public record ExpenseResponse(Long id, String reference, ExpenseCategory category, String categoryLabel,
                                  String label, String supplier, BigDecimal amount, LocalDate expenseDate,
                                  Expense.Status status, String paymentAccount, Long justificationDocumentId,
                                  int requiredApprovals, long approvalsCount, Long createdBy, Instant createdAt,
                                  Instant submittedAt, Instant paidAt, Long paidBy, Long accountingEntryId,
                                  String cancelReason, boolean demo, List<ApprovalItem> approvals) {
    }

    public record ApprovalItem(Long approverId, String approverName, ExpenseApproval.Decision decision,
                               String comment, Instant decidedAt) {
    }

    // ------------------------------------------------------------ budget

    public record BudgetRequest(@Min(2000) @Max(2200) int fiscalYear, @NotBlank @Size(max = 200) String label,
                                @NotNull @Valid List<BudgetLineRequest> lines) {
    }

    public record BudgetLineRequest(@NotBlank String category, @NotNull BudgetLine.Direction direction,
                                    @NotNull @PositiveOrZero BigDecimal plannedAmount) {
    }

    public record AdoptRequest(@NotBlank @Size(min = 5, max = 300) String adoptedReference) {
    }

    public record BudgetResponse(Long id, int fiscalYear, String label, Budget.Status status, String adoptedReference,
                                 List<BudgetExecutionLine> lines, BigDecimal plannedRevenue,
                                 BigDecimal realizedRevenue, BigDecimal plannedExpense, BigDecimal realizedExpense,
                                 BigDecimal committedExpense) {
    }

    /** realized = montant réellement constaté en comptabilité (hors simulation) ; committed = dépenses approuvées non payées. */
    public record BudgetExecutionLine(String category, String label, BudgetLine.Direction direction,
                                      BigDecimal planned, BigDecimal realized, BigDecimal committed,
                                      BigDecimal remaining, Integer executionRatePercent) {
    }

    // ------------------------------------------------------------ comptabilité

    public record ManualEntryRequest(@NotNull LocalDate entryDate, @NotBlank @Size(max = 500) String label,
                                     @NotBlank @Size(min = 10, max = 500) String justification,
                                     @NotNull @Size(min = 2) @Valid List<ManualLine> lines) {
    }

    public record ManualLine(@NotBlank String accountCode, @NotNull @PositiveOrZero BigDecimal debit,
                             @NotNull @PositiveOrZero BigDecimal credit, Long memberId) {
    }

    public record EntryResponse(Long id, String reference, LocalDate entryDate, String label, String sourceType,
                                Long sourceId, Long reversalOfId, Long reversedById, boolean simulated,
                                Instant createdAt, List<LineResponse> lines) {
    }

    public record LineResponse(String accountCode, String accountLabel, BigDecimal debit, BigDecimal credit,
                               Long memberId) {
    }

    public record AccountBalance(String code, String label, String type, boolean treasury, BigDecimal debit,
                                 BigDecimal credit, BigDecimal balance) {
    }

    // ------------------------------------------------------------ banque et rapprochement

    public record DeclarationRequest(@NotBlank String accountCode, @NotNull LocalDate balanceDate,
                                     @NotNull BigDecimal balance, @Size(max = 200) String statementReference) {
    }

    public record DeclarationResponse(Long id, String accountCode, LocalDate balanceDate, BigDecimal balance,
                                      BankBalanceDeclaration.Source source, String statementReference,
                                      Long declaredBy, Instant declaredAt) {
    }

    public record StatementLineResponse(Long id, String accountCode, LocalDate operationDate, String label,
                                        BigDecimal amount, String bankReference, Long matchedEntryId,
                                        String matchedEntryReference, Instant matchedAt) {
    }

    public record ImportResult(int imported, int duplicates, List<String> errors) {
    }

    public record MatchRequest(@NotNull Long entryId) {
    }

    public record ReconciliationAccount(String accountCode, String label, BigDecimal accountingBalance,
                                        BigDecimal declaredBalance, LocalDate declaredAt,
                                        BankBalanceDeclaration.Source declaredSource, BigDecimal gap,
                                        long unmatchedStatementLines, String status) {
    }

    // ------------------------------------------------------------ transparence

    /**
     * Synthèse financière : chaque chiffre est d'une nature explicite. Aucun montant simulé n'est additionné
     * aux montants réels, et le solde déclaré n'est jamais confondu avec le solde comptable.
     */
    public record TransparencySummary(LocalDate asOf, List<ReconciliationAccount> treasury,
                                      BigDecimal accountingTreasuryTotal, BigDecimal declaredTreasuryTotal,
                                      BigDecimal confirmedPaymentsTotal, long confirmedPaymentsCount,
                                      BigDecimal pendingPaymentsTotal, long pendingPaymentsCount,
                                      BigDecimal simulatedPaymentsTotal, long simulatedPaymentsCount,
                                      BigDecimal simulationAccountBalance, BigDecimal memberFundsHeld,
                                      BigDecimal penaltiesIncome, BigDecimal expensesPaidTotal,
                                      BigDecimal expensesCommittedTotal, long expensesAwaitingApproval,
                                      String escrowStatement, boolean escrowIntegrated, Instant lastEntryAt,
                                      Map<String, String> definitions) {
    }

    // ------------------------------------------------------------ rapports

    public record ReportRequest(@NotNull FinancialReport.Type type, @NotNull LocalDate periodStart,
                                @NotNull LocalDate periodEnd) {
    }

    public record ReportResponse(Long id, String reference, FinancialReport.Type type, LocalDate periodStart,
                                 LocalDate periodEnd, FinancialReport.Status status, Long documentId,
                                 Instant generatedAt, Instant publishedAt, Object snapshot) {
    }
}
