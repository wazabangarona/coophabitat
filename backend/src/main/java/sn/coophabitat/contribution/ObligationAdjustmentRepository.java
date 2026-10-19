package sn.coophabitat.contribution;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ObligationAdjustmentRepository extends JpaRepository<ObligationAdjustment, Long> {
    List<ObligationAdjustment> findByObligationIdOrderByIdAsc(Long obligationId);
}
