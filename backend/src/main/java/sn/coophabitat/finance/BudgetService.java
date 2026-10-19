package sn.coophabitat.finance;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.coophabitat.accounting.AccountingLineRepository;
import sn.coophabitat.accounting.Accounts;
import sn.coophabitat.audit.AuditCategory;
import sn.coophabitat.audit.AuditService;
import sn.coophabitat.common.BusinessException;
import sn.coophabitat.common.Money;
import sn.coophabitat.finance.FinanceDtos.*;
import sn.coophabitat.security.CurrentUser;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/** Budget prévisionnel annuel et suivi de son exécution à partir de la comptabilité réelle (hors simulation). */
@Service
public class BudgetService {

    private final BudgetRepository budgets;
    private final BudgetLineRepository budgetLines;
    private final AccountingLineRepository lines;
    private final ExpenseRepository expenses;
    private final AuditService audit;

    public BudgetService(BudgetRepository budgets, BudgetLineRepository budgetLines, AccountingLineRepository lines,
                         ExpenseRepository expenses, AuditService audit) {
        this.budgets = budgets;
        this.budgetLines = budgetLines;
        this.lines = lines;
        this.expenses = expenses;
        this.audit = audit;
    }

    @Transactional
    public Budget create(BudgetRequest r, CurrentUser user) {
        if (budgets.findByFiscalYear(r.fiscalYear()).isPresent()) {
            throw BusinessException.conflict("BUDGET_EXISTANT", "Un budget existe déjà pour l'exercice " + r.fiscalYear());
        }
        Budget b = new Budget();
        b.setFiscalYear(r.fiscalYear());
        b.setLabel(r.label());
        b.setCreatedBy(user.userId());
        b = budgets.save(b);
        replaceLines(b, r.lines());
        audit.record(AuditCategory.FINANCE, "BUDGET_CREE", "Budget", b.getId(), Map.of("exercice", r.fiscalYear()));
        return b;
    }

    @Transactional
    public Budget update(Long id, BudgetRequest r) {
        Budget b = get(id);
        if (b.getStatus() != Budget.Status.BROUILLON) {
            throw BusinessException.rule("BUDGET_ADOPTE", "Un budget adopté n'est plus modifiable");
        }
        if (b.getFiscalYear() != r.fiscalYear()) {
            throw BusinessException.badRequest("EXERCICE", "L'exercice d'un budget ne change pas");
        }
        b.setLabel(r.label());
        replaceLines(b, r.lines());
        audit.record(AuditCategory.FINANCE, "BUDGET_MODIFIE", "Budget", id, Map.of());
        return b;
    }

    @Transactional
    public Budget adopt(Long id, String adoptedReference) {
        Budget b = get(id);
        if (b.getStatus() != Budget.Status.BROUILLON) {
            throw BusinessException.rule("ETAPE_INVALIDE", "Budget déjà adopté ou clôturé");
        }
        b.setStatus(Budget.Status.ADOPTE);
        b.setAdoptedReference(adoptedReference);
        audit.record(AuditCategory.FINANCE, "BUDGET_ADOPTE", "Budget", id, Map.of("decision", adoptedReference));
        return b;
    }

    @Transactional
    public Budget close(Long id) {
        Budget b = get(id);
        if (b.getStatus() != Budget.Status.ADOPTE) {
            throw BusinessException.rule("ETAPE_INVALIDE", "Seul un budget adopté peut être clôturé");
        }
        b.setStatus(Budget.Status.CLOTURE);
        audit.record(AuditCategory.FINANCE, "BUDGET_CLOTURE", "Budget", id, Map.of());
        return b;
    }

    @Transactional(readOnly = true)
    public Budget get(Long id) {
        return budgets.findById(id).orElseThrow(() -> BusinessException.notFound("Budget"));
    }

    @Transactional(readOnly = true)
    public List<Budget> list() {
        return budgets.findAllByOrderByFiscalYearDesc();
    }

    @Transactional(readOnly = true)
    public BudgetResponse execution(Budget b) {
        LocalDate from = LocalDate.of(b.getFiscalYear(), 1, 1);
        LocalDate to = LocalDate.of(b.getFiscalYear(), 12, 31);
        Map<String, BigDecimal[]> movements = new HashMap<>();
        for (Object[] row : lines.movementsByAccount(from, to, false)) {
            movements.put((String) row[0], new BigDecimal[]{(BigDecimal) row[1], (BigDecimal) row[2]});
        }
        Map<ExpenseCategory, BigDecimal> committed = new EnumMap<>(ExpenseCategory.class);
        for (Object[] row : expenses.committedByCategory(from, to)) {
            committed.put((ExpenseCategory) row[0], (BigDecimal) row[1]);
        }
        List<BudgetExecutionLine> out = new ArrayList<>();
        BigDecimal pr = BigDecimal.ZERO, rr = BigDecimal.ZERO, pe = BigDecimal.ZERO, re = BigDecimal.ZERO, ce = BigDecimal.ZERO;
        for (BudgetLine l : budgetLines.findByBudgetIdOrderByDirectionAscCategoryAsc(b.getId())) {
            BigDecimal realized;
            BigDecimal engaged = BigDecimal.ZERO;
            String label;
            if (l.getDirection() == BudgetLine.Direction.RECETTE) {
                String account = "COTISATIONS".equals(l.getCategory()) ? Accounts.MEMBER_CONTRIBUTIONS : Accounts.PENALTY_INCOME;
                realized = creditNet(movements.get(account));
                label = "COTISATIONS".equals(l.getCategory()) ? "Cotisations et apports des membres" : "Pénalités de retard";
                pr = pr.add(l.getPlannedAmount());
                rr = rr.add(realized);
            } else {
                ExpenseCategory cat = ExpenseCategory.valueOf(l.getCategory());
                realized = debitNet(movements.get(cat.accountCode()));
                engaged = committed.getOrDefault(cat, BigDecimal.ZERO);
                label = cat.label();
                pe = pe.add(l.getPlannedAmount());
                re = re.add(realized);
                ce = ce.add(engaged);
            }
            BigDecimal remaining = l.getPlannedAmount().subtract(realized).subtract(engaged);
            Integer rate = l.getPlannedAmount().signum() == 0 ? null
                    : realized.multiply(BigDecimal.valueOf(100)).divide(l.getPlannedAmount(), 0, RoundingMode.HALF_UP).intValue();
            out.add(new BudgetExecutionLine(l.getCategory(), label, l.getDirection(), l.getPlannedAmount(), realized,
                    engaged, remaining, rate));
        }
        return new BudgetResponse(b.getId(), b.getFiscalYear(), b.getLabel(), b.getStatus(), b.getAdoptedReference(),
                out, pr, rr, pe, re, ce);
    }

    private static BigDecimal creditNet(BigDecimal[] m) {
        return m == null ? BigDecimal.ZERO : m[1].subtract(m[0]);
    }

    private static BigDecimal debitNet(BigDecimal[] m) {
        return m == null ? BigDecimal.ZERO : m[0].subtract(m[1]);
    }

    private void replaceLines(Budget b, List<BudgetLineRequest> requested) {
        Set<String> seen = new HashSet<>();
        for (BudgetLineRequest r : requested) {
            boolean valid = r.direction() == BudgetLine.Direction.RECETTE
                    ? BudgetLine.REVENUE_CATEGORIES.contains(r.category())
                    : Arrays.stream(ExpenseCategory.values()).anyMatch(c -> c.name().equals(r.category()));
            if (!valid) {
                throw BusinessException.badRequest("CATEGORIE_INVALIDE", "Catégorie budgétaire inconnue : " + r.category());
            }
            if (!seen.add(r.direction() + ":" + r.category())) {
                throw BusinessException.badRequest("CATEGORIE_DOUBLON", "Catégorie en double : " + r.category());
            }
        }
        budgetLines.deleteAll(budgetLines.findByBudgetIdOrderByDirectionAscCategoryAsc(b.getId()));
        budgetLines.flush();
        for (BudgetLineRequest r : requested) {
            BudgetLine l = new BudgetLine();
            l.setBudgetId(b.getId());
            l.setCategory(r.category());
            l.setDirection(r.direction());
            l.setPlannedAmount(Money.requireWholeXof(r.plannedAmount(), "Le montant prévu"));
            budgetLines.save(l);
        }
    }
}
