package sn.coophabitat.finance;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.coophabitat.accounting.AccountingEntryRepository;
import sn.coophabitat.accounting.AccountingLineRepository;
import sn.coophabitat.accounting.Accounts;
import sn.coophabitat.admin.CoopSettings;
import sn.coophabitat.admin.CoopSettingsRepository;
import sn.coophabitat.finance.FinanceDtos.ReconciliationAccount;
import sn.coophabitat.finance.FinanceDtos.TransparencySummary;
import sn.coophabitat.payment.PaymentIntentRepository;
import sn.coophabitat.payment.PaymentStatus;
import sn.coophabitat.payment.PaymentTransaction;
import sn.coophabitat.payment.PaymentTransactionRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Synthèse de transparence financière : chaque montant a une définition explicite et une nature distincte. */
@Service
public class TransparencyService {

    public static final Map<String, String> DEFINITIONS;

    static {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("accountingTreasuryTotal", "Somme des soldes comptables des comptes de trésorerie réels (banque, caisse, monnaie "
                + "électronique), calculée à partir des écritures validées, hors simulation.");
        d.put("declaredTreasuryTotal", "Somme des derniers soldes déclarés par un responsable ou lus sur un relevé importé. "
                + "Ce n'est pas un solde certifié par la banque.");
        d.put("gap", "Écart = solde déclaré − solde comptable à la date de la déclaration. Un écart non nul doit être expliqué.");
        d.put("confirmedPaymentsTotal", "Paiements réels confirmés par webhook signé, vérification auprès du fournisseur "
                + "ou double validation d'un encaissement manuel.");
        d.put("pendingPaymentsTotal", "Demandes de paiement initiées ou en vérification. Elles ne constituent pas des fonds reçus.");
        d.put("simulatedPaymentsTotal", "Paiements de test (mode simulation). AUCUN ARGENT RÉEL TRANSFÉRÉ ; jamais additionnés aux montants réels.");
        d.put("memberFundsHeld", "Solde créditeur du compte 467100 : apports et cotisations reçus des membres pour les programmes.");
        d.put("penaltiesIncome", "Pénalités de retard effectivement encaissées (compte 758100).");
        d.put("expensesPaidTotal", "Dépenses approuvées et payées, enregistrées en comptabilité.");
        d.put("expensesCommittedTotal", "Dépenses approuvées mais non encore payées (engagements).");
        d.put("escrowStatement", "Situation du cantonnement des fonds. Tant qu'aucun séquestre externe n'est réellement intégré, "
                + "les fonds sont seulement suivis comptablement.");
        DEFINITIONS = Map.copyOf(d);
    }

    private final BankReconciliationService bank;
    private final AccountingLineRepository lines;
    private final AccountingEntryRepository entries;
    private final PaymentTransactionRepository transactions;
    private final PaymentIntentRepository intents;
    private final ExpenseRepository expenses;
    private final CoopSettingsRepository settings;
    private final Clock clock;

    public TransparencyService(BankReconciliationService bank, AccountingLineRepository lines,
                               AccountingEntryRepository entries, PaymentTransactionRepository transactions,
                               PaymentIntentRepository intents, ExpenseRepository expenses,
                               CoopSettingsRepository settings, Clock clock) {
        this.bank = bank;
        this.lines = lines;
        this.entries = entries;
        this.transactions = transactions;
        this.intents = intents;
        this.expenses = expenses;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TransparencySummary summary(LocalDate asOf) {
        LocalDate date = asOf == null ? LocalDate.now(clock) : asOf;
        List<ReconciliationAccount> treasury = bank.reconciliation(date);
        BigDecimal accountingTotal = treasury.stream().map(ReconciliationAccount::accountingBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal declaredTotal = treasury.stream().map(ReconciliationAccount::declaredBalance).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        CoopSettings s = settings.current();
        return new TransparencySummary(date, treasury, accountingTotal, declaredTotal,
                transactions.sumConfirmed(false), transactions.countByStatusAndSimulated(PaymentTransaction.Status.CONFIRME, false),
                intents.sumByStatuses(PaymentStatus.OPEN, false), intents.countByStatusInAndSimulated(PaymentStatus.OPEN, false),
                transactions.sumConfirmed(true), transactions.countByStatusAndSimulated(PaymentTransaction.Status.CONFIRME, true),
                lines.balance(List.of(Accounts.SIMULATION), date, true),
                lines.balance(List.of(Accounts.MEMBER_CONTRIBUTIONS), date, false).negate(),
                lines.balance(List.of(Accounts.PENALTY_INCOME), date, false).negate(),
                expenses.sumByStatuses(EnumSet.of(Expense.Status.PAYEE)),
                expenses.sumByStatuses(EnumSet.of(Expense.Status.APPROUVEE)),
                expenses.countByStatus(Expense.Status.SOUMISE),
                s.isEscrowIntegrated() ? s.getEscrowStatement()
                        : "Fonds suivis comptablement — séquestre externe non vérifié ou non intégré",
                s.isEscrowIntegrated(), entries.lastEntryAt(), DEFINITIONS);
    }
}
