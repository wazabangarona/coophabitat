package sn.coophabitat.accounting;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AccountingEntryRepository extends JpaRepository<AccountingEntry, Long> {

    @Query("""
            select e from AccountingEntry e
            where (cast(:from as LocalDate) is null or e.entryDate >= :from) and (cast(:to as LocalDate) is null or e.entryDate <= :to)
              and (:simulated is null or e.simulated = :simulated)
            order by e.entryDate desc, e.id desc
            """)
    Page<AccountingEntry> search(LocalDate from, LocalDate to, Boolean simulated, Pageable pageable);

    List<AccountingEntry> findBySourceTypeAndSourceIdOrderByIdAsc(String sourceType, Long sourceId);

    Optional<AccountingEntry> findByReversalOfId(Long reversalOfId);

    Optional<AccountingEntry> findByReference(String reference);

    @Query("select max(e.createdAt) from AccountingEntry e")
    Instant lastEntryAt();
}
