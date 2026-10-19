package sn.coophabitat.allocation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AllocationMinutesRepository extends JpaRepository<AllocationMinutes, Long> {

    List<AllocationMinutes> findByCampaignIdOrderByVersionNoAsc(Long campaignId);

    Optional<AllocationMinutes> findTopByCampaignIdOrderByVersionNoDesc(Long campaignId);
}
