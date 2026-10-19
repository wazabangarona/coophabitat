package sn.coophabitat.allocation;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AllocationCampaignRepository extends JpaRepository<AllocationCampaign, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AllocationCampaign c where c.id = :id")
    Optional<AllocationCampaign> lockById(Long id);

    List<AllocationCampaign> findAllByOrderByCreatedAtDesc();

    List<AllocationCampaign> findByStatusNotInOrderByCreatedAtDesc(Collection<CampaignStatus> statuses);

    long countByStatusIn(Collection<CampaignStatus> statuses);
}
