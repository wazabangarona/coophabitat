package sn.coophabitat.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long>, JpaSpecificationExecutor<AuditEvent> {

    Optional<AuditEvent> findTopByOrderByIdDesc();

    List<AuditEvent> findByEntityTypeAndEntityIdOrderByIdAsc(String entityType, String entityId);

    @Query("select e from AuditEvent e where e.id > :afterId order by e.id asc")
    List<AuditEvent> findChunk(Long afterId, Pageable pageable);

    Page<AuditEvent> findByActorUserIdOrderByIdDesc(Long actorUserId, Pageable pageable);
}
