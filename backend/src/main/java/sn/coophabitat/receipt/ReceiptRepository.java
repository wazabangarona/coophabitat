package sn.coophabitat.receipt;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReceiptRepository extends JpaRepository<Receipt, Long> {

    Optional<Receipt> findByReceiptNumber(String receiptNumber);

    Optional<Receipt> findByTransactionId(Long transactionId);

    List<Receipt> findByMemberIdOrderByIssuedAtDesc(Long memberId);
}
