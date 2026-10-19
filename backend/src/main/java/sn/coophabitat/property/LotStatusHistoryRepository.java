package sn.coophabitat.property;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LotStatusHistoryRepository extends JpaRepository<LotStatusHistory, Long> {
    List<LotStatusHistory> findByLotIdOrderByChangedAtAscIdAsc(Long lotId);
}
