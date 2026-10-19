package sn.coophabitat.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import sn.coophabitat.accounting.AccountingService;
import sn.coophabitat.accounting.AccountingService.LineSpec;
import sn.coophabitat.accounting.Accounts;
import sn.coophabitat.common.BusinessException;
import sn.coophabitat.document.*;
import sn.coophabitat.finance.*;
import sn.coophabitat.finance.FinanceDtos.*;
import sn.coophabitat.security.CurrentUser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Dépenses (séparation des tâches), rapprochement bancaire, transparence et rapports. */
class FinanceIT extends IntegrationTest {

    @Autowired ExpenseService expenses;
    @Autowired BankReconciliationService bank;
    @Autowired TransparencyService transparency;
    @Autowired FinancialReportService reports;
    @Autowired AccountingService accounting;
    @Autowired DocumentService documents;

    private Long justification() {
        byte[] pdf = "%PDF-1.7\n% justificatif de test\n".getBytes();
        return documents.store(new DocumentService.NewDocument("Justificatif", DocumentCategory.JUSTIFICATIF_DEPENSE,
                DocumentVisibility.BUREAU, null, null, null, "j.pdf", "application/pdf", pdf, false, false)).getId();
    }

    private void fundCash(long amount) {
        accounting.post(LocalDate.now(), "Apport de test", "MANUELLE", null, false, List.of(
                LineSpec.debit(Accounts.CASH, BigDecimal.valueOf(amount), null),
                LineSpec.credit(Accounts.MEMBER_CONTRIBUTIONS, BigDecimal.valueOf(amount), null)));
    }

    @Test
    void expenseWorkflowEnforcesSeparationOfDuties() {
        CurrentUser treasurer = fx.actAs(fx.user(null, "TRESORIER"));
        fundCash(100_000);
        Expense e = expenses.create(new ExpenseRequest(ExpenseCategory.FOURNITURES, "Ramettes", "Papeterie",
                BigDecimal.valueOf(20_000), LocalDate.now(), null), treasurer);
        assertThatThrownBy(() -> expenses.submit(e.getId(), treasurer)).hasMessageContaining("justificative");
        expenses.attachJustification(e.getId(), justification(), treasurer);
        expenses.submit(e.getId(), treasurer);
        assertThatThrownBy(() -> expenses.decide(e.getId(), new ExpenseDecisionRequest(ExpenseApproval.Decision.APPROUVEE, null),
                treasurer)).hasMessageContaining("Séparation");
        CurrentUser president = fx.actAs(fx.user(null, "PRESIDENT"));
        expenses.decide(e.getId(), new ExpenseDecisionRequest(ExpenseApproval.Decision.APPROUVEE, "Conforme"), president);
        assertThatThrownBy(() -> expenses.pay(e.getId(), new ExpensePayRequest(Accounts.CASH, null), president))
                .hasMessageContaining("approbateur");
        assertThatThrownBy(() -> expenses.pay(e.getId(), new ExpensePayRequest(Accounts.SIMULATION, null), treasurer))
                .isInstanceOf(BusinessException.class);
        fx.actAs(fx.user(null, "TRESORIER"));
        Expense paid = expenses.pay(e.getId(), new ExpensePayRequest(Accounts.CASH, null), treasurer);
        assertThat(paid.getStatus()).isEqualTo(Expense.Status.PAYEE);
        assertThat(jdbc.queryForObject("SELECT debit FROM accounting_line WHERE entry_id = ? AND account_code = '604100'",
                BigDecimal.class, paid.getAccountingEntryId())).isEqualByComparingTo("20000");
        // Annulation d'une dépense payée = contre-écriture, jamais suppression.
        expenses.cancel(e.getId(), "Fournitures retournées au fournisseur", treasurer);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounting_entry WHERE reversal_of_id = ?", Long.class,
                paid.getAccountingEntryId())).isEqualTo(1);
    }

    @Test
    void largeExpensesNeedTwoDistinctApprovals() {
        CurrentUser treasurer = fx.actAs(fx.user(null, "TRESORIER"));
        Expense e = expenses.create(new ExpenseRequest(ExpenseCategory.HONORAIRES_ETUDES, "Étude géotechnique", "Bureau d'études",
                BigDecimal.valueOf(750_000), LocalDate.now(), justification()), treasurer);
        expenses.submit(e.getId(), treasurer);
        assertThat(expenses.get(e.getId()).getRequiredApprovals()).isEqualTo(2);
        expenses.decide(e.getId(), new ExpenseDecisionRequest(ExpenseApproval.Decision.APPROUVEE, null),
                fx.current(fx.user(null, "PRESIDENT")));
        assertThat(expenses.get(e.getId()).getStatus()).isEqualTo(Expense.Status.SOUMISE);
        expenses.decide(e.getId(), new ExpenseDecisionRequest(ExpenseApproval.Decision.APPROUVEE, null),
                fx.current(fx.user(null, "PRESIDENT")));
        assertThat(expenses.get(e.getId()).getStatus()).isEqualTo(Expense.Status.APPROUVEE);
    }

    @Test
    void bankStatementImportIsIdempotentAndMatchingChecksAmounts() {
        CurrentUser treasurer = fx.actAs(fx.user(null, "TRESORIER"));
        var entry = accounting.post(LocalDate.now(), "Virement reçu", "MANUELLE", null, false, List.of(
                LineSpec.debit(Accounts.BANK, BigDecimal.valueOf(42_000), null),
                LineSpec.credit(Accounts.MEMBER_CONTRIBUTIONS, BigDecimal.valueOf(42_000), null)));
        String ref = "REL-" + Fixtures.next();
        String csv = "date;libelle;montant;reference\n" + LocalDate.now() + ";Virement membre;42 000;" + ref + "\n"
                + "31/12/2025;Frais;-1500;" + ref + "-F\nligne invalide\n";
        ImportResult first = bank.importStatement(Accounts.BANK, csv.getBytes(), treasurer);
        ImportResult again = bank.importStatement(Accounts.BANK, csv.getBytes(), treasurer);
        assertThat(first.imported()).isEqualTo(2);
        assertThat(first.errors()).hasSize(1);
        assertThat(again.imported()).isZero();
        assertThat(again.duplicates()).isEqualTo(2);
        var lines = bank.statement(Accounts.BANK).stream().filter(l -> ref.equals(l.bankReference())).toList();
        Long lineId = lines.get(0).id();
        Long feeLine = bank.statement(Accounts.BANK).stream().filter(l -> (ref + "-F").equals(l.bankReference())).findFirst().orElseThrow().id();
        assertThatThrownBy(() -> bank.match(feeLine, entry.getId(), treasurer)).hasMessageContaining("même montant");
        bank.match(lineId, entry.getId(), treasurer);
        assertThatThrownBy(() -> bank.match(lineId, entry.getId(), treasurer)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> bank.importStatement(Accounts.SIMULATION, csv.getBytes(), treasurer)).isInstanceOf(BusinessException.class);
    }

    @Test
    void transparencyKeepsSimulatedDeclaredAndAccountingAmountsApart() {
        CurrentUser treasurer = fx.actAs(fx.user(null, "TRESORIER"));
        accounting.post(LocalDate.now(), "Paiement simulé", "PAIEMENT", null, true, List.of(
                LineSpec.debit(Accounts.SIMULATION, BigDecimal.valueOf(9_999), null),
                LineSpec.credit(Accounts.MEMBER_CONTRIBUTIONS, BigDecimal.valueOf(9_999), null)));
        TransparencySummary before = transparency.summary(LocalDate.now());
        accounting.post(LocalDate.now(), "Paiement simulé 2", "PAIEMENT", null, true, List.of(
                LineSpec.debit(Accounts.SIMULATION, BigDecimal.valueOf(1_000), null),
                LineSpec.credit(Accounts.MEMBER_CONTRIBUTIONS, BigDecimal.valueOf(1_000), null)));
        TransparencySummary after = transparency.summary(LocalDate.now());
        assertThat(after.accountingTreasuryTotal()).isEqualByComparingTo(before.accountingTreasuryTotal());
        assertThat(after.memberFundsHeld()).isEqualByComparingTo(before.memberFundsHeld());
        assertThat(after.simulationAccountBalance().subtract(before.simulationAccountBalance())).isEqualByComparingTo("1000");
        assertThat(after.escrowStatement()).isEqualTo("Fonds suivis comptablement — séquestre externe non vérifié ou non intégré");
        assertThat(after.escrowIntegrated()).isFalse();

        LocalDate d = LocalDate.now().minusDays(Fixtures.next() % 300 + 1);
        bank.declare(new DeclarationRequest(Accounts.WAVE, d, BigDecimal.valueOf(123), "Relevé test"),
                BankBalanceDeclaration.Source.DECLARATION, treasurer);
        var wave = bank.reconciliation(LocalDate.now()).stream().filter(a -> a.accountCode().equals(Accounts.WAVE)).findFirst().orElseThrow();
        assertThat(wave.declaredSource()).isEqualTo(BankBalanceDeclaration.Source.DECLARATION);
        assertThat(wave.gap()).isNotNull();
    }

    @Test
    void reportsAreFrozenAndPublishedByAnotherOfficer() throws Exception {
        CurrentUser treasurer = fx.actAs(fx.user(null, "TRESORIER"));
        FinancialReport r = reports.generate(new ReportRequest(FinancialReport.Type.PERIODE, LocalDate.now().minusMonths(1),
                LocalDate.now()), treasurer);
        assertThat(r.getSnapshot()).contains("escrowStatement");
        assertThat(r.getDocumentId()).isNotNull();
        assertThatThrownBy(() -> reports.publish(r.getId(), treasurer)).hasMessageContaining("Séparation");
        String memberToken = fx.token(fx.user(null, "MEMBRE"));
        mvc.perform(get("/api/v1/finance/reports/" + r.getId()).header("Authorization", bearer(memberToken)))
                .andExpect(status().isNotFound());
        reports.publish(r.getId(), fx.current(fx.user(null, "PRESIDENT")));
        mvc.perform(get("/api/v1/finance/reports/" + r.getId()).header("Authorization", bearer(memberToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLIE"));
    }

    @Test
    void uploadsAreCheckedByContentAndPrivateDocumentsStayPrivate() throws Exception {
        String sec = fx.token(fx.user(null, "SECRETAIRE"));
        MockMultipartFile fake = new MockMultipartFile("file", "facture.pdf", "application/pdf", "<script>alert(1)</script>".getBytes());
        mvc.perform(multipart("/api/v1/documents").file(fake).param("title", "Faux PDF").param("category", "AUTRE")
                .param("visibility", "BUREAU").header("Authorization", bearer(sec)))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(400, 415, 422));
        MockMultipartFile pdf = new MockMultipartFile("file", "statuts.pdf", "application/pdf", "%PDF-1.7\n...".getBytes());
        String body = mvc.perform(multipart("/api/v1/documents").file(pdf).param("title", "Note du bureau").param("category", "AUTRE")
                        .param("visibility", "BUREAU").header("Authorization", bearer(sec)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) sn.coophabitat.common.Json.read(body, java.util.Map.class).get("id")).longValue();
        String memberToken = fx.token(fx.user(null, "MEMBRE"));
        mvc.perform(get("/api/v1/documents/" + id + "/content").header("Authorization", bearer(memberToken)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/documents/" + id + "/content").header("Authorization", bearer(sec)))
                .andExpect(status().isOk());
    }
}
