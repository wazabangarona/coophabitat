package sn.coophabitat.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BankBalanceDeclarationRepository extends JpaRepository<BankBalanceDeclaration, Long> {

    Optional<BankBalanceDeclaration> findTopByAccountCodeOrderByBalanceDateDescIdDesc(String accountCode);

    List<BankBalanceDeclaration> findByAccountCodeOrderByBalanceDateDescIdDesc(String accountCode);

    boolean existsByAccountCodeAndBalanceDateAndSource(String accountCode, java.time.LocalDate date,
                                                       BankBalanceDeclaration.Source source);
}
