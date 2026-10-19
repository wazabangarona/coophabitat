package sn.coophabitat.finance;

import org.openpdf.text.Element;
import org.openpdf.text.pdf.PdfPTable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.coophabitat.accounting.AccountingLineRepository;
import sn.coophabitat.accounting.Accounts;
import sn.coophabitat.admin.CoopSettingsRepository;
import sn.coophabitat.audit.AuditCategory;
import sn.coophabitat.audit.AuditService;
import sn.coophabitat.common.BusinessException;
import sn.coophabitat.common.Json;
import sn.coophabitat.common.Money;
import sn.coophabitat.common.ReferenceGenerator;
import sn.coophabitat.config.ClockConfig;
import sn.coophabitat.document.*;
import sn.coophabitat.finance.FinanceDtos.*;
import sn.coophabitat.member.Member;
import sn.coophabitat.member.MemberRepository;
import sn.coophabitat.member.MemberStatus;
import sn.coophabitat.notification.NotificationService;
import sn.coophabitat.payment.PaymentTransaction;
import sn.coophabitat.payment.PaymentTransactionRepository;
import sn.coophabitat.reporting.PdfSupport;
import sn.coophabitat.security.CurrentUser;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Rapports financiers périodiques. Le contenu est figé dans un instantané JSON au moment de la génération :
 * il ne change plus, même si de nouvelles écritures sont passées ensuite (une nouvelle version doit être générée).
 */
@Service
public class FinancialReportService {

    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final FinancialReportRepository reports;
    private final LedgerService ledger;
    private final BankReconciliationService bank;
    private final BudgetService budgets;
    private final BudgetRepository budgetRepository;
    private final ExpenseRepository expenses;
    private final AccountingLineRepository lines;
    private final PaymentTransactionRepository transactions;
    private final DocumentService documents;
    private final MemberRepository members;
    private final NotificationService notifications;
    private final CoopSettingsRepository settings;
    private final ReferenceGenerator references;
    private final AuditService audit;
    private final Clock clock;

    public FinancialReportService(FinancialReportRepository reports, LedgerService ledger, BankReconciliationService bank,
                                  BudgetService budgets, BudgetRepository budgetRepository, ExpenseRepository expenses,
                                  AccountingLineRepository lines, PaymentTransactionRepository transactions,
                                  DocumentService documents, MemberRepository members, NotificationService notifications,
                                  CoopSettingsRepository settings, ReferenceGenerator references, AuditService audit,
                                  Clock clock) {
        this.reports = reports;
        this.ledger = ledger;
        this.bank = bank;
        this.budgets = budgets;
        this.budgetRepository = budgetRepository;
        this.expenses = expenses;
        this.lines = lines;
        this.transactions = transactions;
        this.documents = documents;
        this.members = members;
        this.notifications = notifications;
        this.settings = settings;
        this.references = references;
        this.audit = audit;
        this.clock = clock;
    }

    public record Snapshot(String periodStart, String periodEnd, String generatedAt,
                           BigDecimal contributionsReceived, BigDecimal penaltiesReceived, BigDecimal expensesPaid,
                           BigDecimal confirmedRealPayments, BigDecimal simulatedPayments,
                           List<AccountBalance> balances, List<CategoryAmount> expensesByCategory,
                           List<ReconciliationAccount> treasury, BudgetResponse budget, String escrowStatement,
                           Map<String, String> definitions) {
    }

    public record CategoryAmount(String category, String label, BigDecimal amount) {
    }

    @Transactional
    public FinancialReport generate(ReportRequest r, CurrentUser user) {
        if (r.periodEnd().isBefore(r.periodStart())) {
            throw BusinessException.badRequest("PERIODE_INVALIDE", "La fin de période précède son début");
        }
        if (r.periodEnd().isAfter(LocalDate.now(clock))) {
            throw BusinessException.badRequest("PERIODE_FUTURE", "La période ne peut pas se terminer dans le futur");
        }
        Snapshot snap = snapshot(r.periodStart(), r.periodEnd());
        FinancialReport report = new FinancialReport();
        report.setReference(references.next("RAP"));
        report.setReportType(r.type());
        report.setPeriodStart(r.periodStart());
        report.setPeriodEnd(r.periodEnd());
        report.setSnapshot(Json.write(snap));
        report.setGeneratedBy(user.userId());
        report.setGeneratedAt(Instant.now(clock));
        report = reports.save(report);
        byte[] pdf = pdf(report, snap);
        StoredDocument doc = documents.store(new DocumentService.NewDocument(
                "Rapport financier " + D.format(r.periodStart()) + " – " + D.format(r.periodEnd()),
                DocumentCategory.RAPPORT_FINANCIER, DocumentVisibility.BUREAU, null, "FinancialReport", report.getId(),
                report.getReference() + ".pdf", "application/pdf", pdf, true, false));
        report.setDocumentId(doc.getId());
        audit.record(AuditCategory.FINANCE, "RAPPORT_GENERE", "FinancialReport", report.getId(),
                Map.of("reference", report.getReference(), "document_sha256", doc.getSha256()));
        return report;
    }

    @Transactional
    public FinancialReport publish(Long id, CurrentUser user) {
        FinancialReport report = get(id);
        if (report.getStatus() == FinancialReport.Status.PUBLIE) {
            throw BusinessException.conflict("DEJA_PUBLIE", "Rapport déjà publié");
        }
        if (report.getGeneratedBy().equals(user.userId())) {
            throw BusinessException.forbidden("Séparation des tâches : le rapport doit être publié par un autre responsable que son auteur");
        }
        report.setStatus(FinancialReport.Status.PUBLIE);
        report.setPublishedBy(user.userId());
        report.setPublishedAt(Instant.now(clock));
        documents.publish(report.getDocumentId(), DocumentVisibility.MEMBRES);
        audit.record(AuditCategory.FINANCE, "RAPPORT_PUBLIE", "FinancialReport", id, Map.of("reference", report.getReference()));
        for (Member m : members.findByStatusIn(List.of(MemberStatus.ACTIF, MemberStatus.SUSPENDU))) {
            notifications.notifyMember(m.getId(), "RAPPORT_FINANCIER", "Nouveau rapport financier disponible",
                    "Le rapport financier de la période du " + D.format(report.getPeriodStart()) + " au "
                            + D.format(report.getPeriodEnd()) + " est consultable dans votre espace membre.",
                    "RAPPORT:" + id + ":" + m.getId(), "FinancialReport", id);
        }
        return report;
    }

    @Transactional(readOnly = true)
    public FinancialReport get(Long id) {
        return reports.findById(id).orElseThrow(() -> BusinessException.notFound("Rapport"));
    }

    @Transactional(readOnly = true)
    public List<FinancialReport> list(boolean publishedOnly) {
        return publishedOnly ? reports.findByStatusOrderByPeriodEndDescIdDesc(FinancialReport.Status.PUBLIE)
                : reports.findAllByOrderByPeriodEndDescIdDesc();
    }

    public ReportResponse toResponse(FinancialReport r, boolean withSnapshot) {
        return new ReportResponse(r.getId(), r.getReference(), r.getReportType(), r.getPeriodStart(), r.getPeriodEnd(),
                r.getStatus(), r.getDocumentId(), r.getGeneratedAt(), r.getPublishedAt(),
                withSnapshot ? Json.read(r.getSnapshot(), Map.class) : null);
    }

    Snapshot snapshot(LocalDate from, LocalDate to) {
        List<AccountBalance> balances = ledger.trialBalance(from, to, false);
        BigDecimal contributions = creditNet(balances, Accounts.MEMBER_CONTRIBUTIONS);
        BigDecimal penalties = creditNet(balances, Accounts.PENALTY_INCOME);
        List<CategoryAmount> byCategory = new ArrayList<>();
        BigDecimal paid = BigDecimal.ZERO;
        for (Object[] row : expenses.paidByCategory(from, to)) {
            ExpenseCategory c = (ExpenseCategory) row[0];
            BigDecimal amount = (BigDecimal) row[1];
            byCategory.add(new CategoryAmount(c.name(), c.label(), amount));
            paid = paid.add(amount);
        }
        Instant start = from.atStartOfDay(ClockConfig.DAKAR).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(ClockConfig.DAKAR).toInstant();
        BudgetResponse budget = budgetRepository.findByFiscalYear(to.getYear()).map(budgets::execution).orElse(null);
        return new Snapshot(from.toString(), to.toString(), Instant.now(clock).toString(), contributions, penalties, paid,
                transactions.sumConfirmedBetween(PaymentTransaction.Status.CONFIRME, false, start, end),
                transactions.sumConfirmedBetween(PaymentTransaction.Status.CONFIRME, true, start, end),
                balances, byCategory, bank.reconciliation(to), budget,
                settings.current().isEscrowIntegrated() ? settings.current().getEscrowStatement()
                        : "Fonds suivis comptablement — séquestre externe non vérifié ou non intégré",
                TransparencyService.DEFINITIONS);
    }

    private static BigDecimal creditNet(List<AccountBalance> balances, String code) {
        return balances.stream().filter(b -> b.code().equals(code)).map(b -> b.credit().subtract(b.debit()))
                .findFirst().orElse(BigDecimal.ZERO);
    }

    private byte[] pdf(FinancialReport report, Snapshot s) {
        return PdfSupport.build(settings.current(), "Rapport financier — " + label(report.getReportType()),
                report.getReference(), report.getGeneratedAt(), doc -> {
                    PdfPTable kv = PdfSupport.keyValueTable();
                    PdfSupport.row(kv, "Période", D.format(report.getPeriodStart()) + " au " + D.format(report.getPeriodEnd()));
                    PdfSupport.row(kv, "Cotisations et apports reçus", Money.format(s.contributionsReceived()));
                    PdfSupport.row(kv, "Pénalités encaissées", Money.format(s.penaltiesReceived()));
                    PdfSupport.row(kv, "Dépenses payées", Money.format(s.expensesPaid()));
                    PdfSupport.row(kv, "Paiements réels confirmés", Money.format(s.confirmedRealPayments()));
                    PdfSupport.row(kv, "Paiements simulés (aucun argent réel)", Money.format(s.simulatedPayments()));
                    PdfSupport.row(kv, "Cantonnement des fonds", s.escrowStatement());
                    doc.add(kv);

                    PdfSupport.section(doc, "Trésorerie : solde comptable et solde déclaré");
                    PdfPTable t = PdfSupport.table(new float[]{14, 30, 18, 18, 20}, "Compte", "Intitulé",
                            "Solde comptable", "Solde déclaré", "Écart / état");
                    for (ReconciliationAccount a : s.treasury()) {
                        PdfSupport.cell(t, a.accountCode());
                        PdfSupport.cell(t, a.label());
                        PdfSupport.cell(t, Money.format(a.accountingBalance()), Element.ALIGN_RIGHT);
                        PdfSupport.cell(t, a.declaredBalance() == null ? "Non déclaré"
                                : Money.format(a.declaredBalance()) + " (" + D.format(a.declaredAt()) + ")", Element.ALIGN_RIGHT);
                        PdfSupport.cell(t, a.gap() == null ? a.status() : Money.format(a.gap()) + " — " + a.status());
                    }
                    doc.add(t);
                    PdfSupport.note(doc, "Le solde déclaré est saisi par un responsable ou lu sur un relevé importé ; "
                            + "il n'est pas certifié par la banque.");

                    PdfSupport.section(doc, "Dépenses payées par catégorie");
                    PdfPTable e = PdfSupport.table(new float[]{60, 40}, "Catégorie", "Montant");
                    for (CategoryAmount c : s.expensesByCategory()) {
                        PdfSupport.cell(e, c.label());
                        PdfSupport.cell(e, Money.format(c.amount()), Element.ALIGN_RIGHT);
                    }
                    if (s.expensesByCategory().isEmpty()) {
                        PdfSupport.cell(e, "Aucune dépense payée sur la période");
                        PdfSupport.cell(e, "");
                    }
                    doc.add(e);

                    if (s.budget() != null) {
                        PdfSupport.section(doc, "Exécution du budget " + s.budget().fiscalYear()
                                + (s.budget().adoptedReference() == null ? " (non adopté)" : " — " + s.budget().adoptedReference()));
                        PdfPTable b = PdfSupport.table(new float[]{34, 12, 18, 18, 18}, "Poste", "Sens", "Prévu", "Réalisé", "Engagé");
                        for (BudgetExecutionLine l : s.budget().lines()) {
                            PdfSupport.cell(b, l.label());
                            PdfSupport.cell(b, l.direction().name());
                            PdfSupport.cell(b, Money.format(l.planned()), Element.ALIGN_RIGHT);
                            PdfSupport.cell(b, Money.format(l.realized()), Element.ALIGN_RIGHT);
                            PdfSupport.cell(b, Money.format(l.committed()), Element.ALIGN_RIGHT);
                        }
                        doc.add(b);
                    }

                    PdfSupport.section(doc, "Balance des comptes (écritures réelles)");
                    PdfPTable bal = PdfSupport.table(new float[]{14, 38, 16, 16, 16}, "Compte", "Intitulé", "Débit", "Crédit", "Solde");
                    for (AccountBalance a : s.balances()) {
                        PdfSupport.cell(bal, a.code());
                        PdfSupport.cell(bal, a.label());
                        PdfSupport.cell(bal, Money.format(a.debit()), Element.ALIGN_RIGHT);
                        PdfSupport.cell(bal, Money.format(a.credit()), Element.ALIGN_RIGHT);
                        PdfSupport.cell(bal, Money.format(a.balance()), Element.ALIGN_RIGHT);
                    }
                    doc.add(bal);
                    PdfSupport.note(doc, "Rapport généré automatiquement à partir de la comptabilité de CoopHabitat. "
                            + "Les montants simulés ne sont jamais inclus dans les totaux réels. "
                            + "Ce document n'est pas un état financier certifié par un commissaire aux comptes.");
                });
    }

    private static String label(FinancialReport.Type t) {
        return switch (t) {
            case SEMESTRIEL -> "semestriel";
            case ANNUEL -> "annuel";
            case PERIODE -> "période";
        };
    }
}
