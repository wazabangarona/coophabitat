package sn.coophabitat.allocation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AllocationCandidateRepository extends JpaRepository<AllocationCandidate, Long> {

    List<AllocationCandidate> findByCampaignIdOrderByIdAsc(Long campaignId);

    Optional<AllocationCandidate> findByCampaignIdAndMemberId(Long campaignId, Long memberId);

    List<AllocationCandidate> findByMemberIdOrderByIdDesc(Long memberId);

    List<AllocationCandidate> findByCampaignIdAndEligibilityStatusAndWithdrawnAtIsNull(Long campaignId,
                                                                                    AllocationCandidate.Eligibility status);
}
