package sn.coophabitat.allocation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AllocationContestRepository extends JpaRepository<AllocationContest, Long> {

    List<AllocationContest> findByCampaignIdOrderByIdAsc(Long campaignId);

    List<AllocationContest> findByMemberIdOrderByIdDesc(Long memberId);

    long countByCampaignIdAndStatusIn(Long campaignId, Collection<AllocationContest.Status> statuses);

    long countByStatusIn(Collection<AllocationContest.Status> statuses);
}
