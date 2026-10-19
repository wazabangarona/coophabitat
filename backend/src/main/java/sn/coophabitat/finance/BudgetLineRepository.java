package sn.coophabitat.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BudgetLineRepository extends JpaRepository<BudgetLine, Long> {

    List<BudgetLine> findByBudgetIdOrderByDirectionAscCategoryAsc(Long budgetId);
}
