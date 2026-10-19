package sn.coophabitat.contribution;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ContributionPlanRepository extends JpaRepository<ContributionPlan, Long> {
    Optional<ContributionPlan> findByCode(String code);

    List<ContributionPlan> findAllByOrderByValidFromDesc();
}
