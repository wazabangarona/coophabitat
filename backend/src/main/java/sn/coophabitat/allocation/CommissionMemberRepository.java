package sn.coophabitat.allocation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommissionMemberRepository extends JpaRepository<CommissionMember, CommissionMember.Key> {
    List<CommissionMember> findByCampaignId(Long campaignId);
}
