package sn.coophabitat.accounting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface AccountingLineRepository extends JpaRepository<AccountingLine, Long> {

    List<AccountingLine> findByEntryIdOrderByIdAsc(Long entryId);

    List<AccountingLine> findByEntryIdInOrderByIdAsc(Collection<Long> entryIds);

    /** Solde (débit − crédit) d'un ensemble de comptes jusqu'à une date, écritures simulées exclues ou seules. */
    @Query("""
            select coalesce(sum(l.debit - l.credit), 0) from AccountingLine l, AccountingEntry e
            where l.entryId = e.id and l.accountCode in :codes and e.entryDate <= :asOf and e.simulated = :simulated
            """)
    BigDecimal balance(Collection<String> codes, LocalDate asOf, boolean simulated);

    @Query("""
            select l.accountCode, coalesce(sum(l.debit), 0), coalesce(sum(l.credit), 0)
            from AccountingLine l, AccountingEntry e
            where l.entryId = e.id and e.entryDate between :from and :to and e.simulated = :simulated
            group by l.accountCode order by l.accountCode
            """)
    List<Object[]> movementsByAccount(LocalDate from, LocalDate to, boolean simulated);
}
