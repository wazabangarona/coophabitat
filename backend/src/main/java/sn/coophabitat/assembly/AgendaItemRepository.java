package sn.coophabitat.assembly;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgendaItemRepository extends JpaRepository<AgendaItem, Long> {

    List<AgendaItem> findByAssemblyIdOrderByPositionAsc(Long assemblyId);
}
