package sn.coophabitat.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BankStatementLineRepository extends JpaRepository<BankStatementLine, Long> {

    List<BankStatementLine> findByAccountCodeOrderByOperationDateDescIdDesc(String accountCode);

    boolean existsByImportHash(String importHash);

    boolean existsByMatchedEntryId(Long entryId);

    long countByAccountCodeAndMatchedEntryIdIsNull(String accountCode);
}
