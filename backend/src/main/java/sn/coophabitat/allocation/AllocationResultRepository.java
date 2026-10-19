package sn.coophabitat.allocation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AllocationResultRepository extends JpaRepository<AllocationResult, Long> {

    List<AllocationResult> findByCampaignIdOrderByDrawRankAscIdAsc(Long campaignId);

    List<AllocationResult> findByCampaignIdAndStatusIn(Long campaignId, Collection<AllocationResult.Status> statuses);

    Optional<AllocationResult> findByCandidateIdAndStatusNot(Long candidateId, AllocationResult.Status status);

    List<AllocationResult> findByLotIdOrderByIdAsc(Long lotId);

    boolean existsByLotIdAndStatusIn(Long lotId, Collection<AllocationResult.Status> statuses);
}
