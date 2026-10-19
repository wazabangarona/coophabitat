package sn.coophabitat.accounting;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FinancialAccountRepository extends JpaRepository<FinancialAccount, String> {
    List<FinancialAccount> findByTreasuryTrue();

    List<FinancialAccount> findAllByOrderByCodeAsc();
}
