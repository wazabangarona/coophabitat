package sn.coophabitat.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BudgetRepository extends JpaRepository<Budget, Long> {

    Optional<Budget> findByFiscalYear(int fiscalYear);

    List<Budget> findAllByOrderByFiscalYearDesc();
}
