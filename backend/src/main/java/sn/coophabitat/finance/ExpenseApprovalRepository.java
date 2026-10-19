package sn.coophabitat.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExpenseApprovalRepository extends JpaRepository<ExpenseApproval, Long> {

    List<ExpenseApproval> findByExpenseIdOrderByIdAsc(Long expenseId);

    boolean existsByExpenseIdAndApproverId(Long expenseId, Long approverId);
}
