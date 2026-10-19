package sn.coophabitat.allocation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AllocationApprovalRepository extends JpaRepository<AllocationApproval, Long> {

    List<AllocationApproval> findByCampaignIdOrderByIdAsc(Long campaignId);

    List<AllocationApproval> findByCampaignIdAndStepAndTargetId(Long campaignId, AllocationApproval.Step step, Long targetId);

    List<AllocationApproval> findByCampaignIdAndStep(Long campaignId, AllocationApproval.Step step);
}
