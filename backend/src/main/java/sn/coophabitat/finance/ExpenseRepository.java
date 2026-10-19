package sn.coophabitat.finance;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Expense e where e.id = :id")
    Optional<Expense> lockById(Long id);

    @Query("""
            select e from Expense e
            where (:status is null or e.status = :status) and (:category is null or e.category = :category)
              and (cast(:from as LocalDate) is null or e.expenseDate >= :from) and (cast(:to as LocalDate) is null or e.expenseDate <= :to)
            order by e.expenseDate desc, e.id desc
            """)
    Page<Expense> search(Expense.Status status, ExpenseCategory category, LocalDate from, LocalDate to, Pageable pageable);

    @Query("select coalesce(sum(e.amount), 0) from Expense e where e.status in :statuses")
    BigDecimal sumByStatuses(Collection<Expense.Status> statuses);

    @Query("""
            select e.category, coalesce(sum(e.amount), 0) from Expense e
            where e.status = sn.coophabitat.finance.Expense.Status.PAYEE and e.expenseDate between :from and :to
            group by e.category
            """)
    List<Object[]> paidByCategory(LocalDate from, LocalDate to);

    @Query("""
            select e.category, coalesce(sum(e.amount), 0) from Expense e
            where e.status = sn.coophabitat.finance.Expense.Status.APPROUVEE and e.expenseDate between :from and :to
            group by e.category
            """)
    List<Object[]> committedByCategory(LocalDate from, LocalDate to);

    long countByStatus(Expense.Status status);
}
