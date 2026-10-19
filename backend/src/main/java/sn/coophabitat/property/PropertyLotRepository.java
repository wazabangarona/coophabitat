package sn.coophabitat.property;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PropertyLotRepository extends JpaRepository<PropertyLot, Long> {

    Optional<PropertyLot> findByReference(String reference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from PropertyLot l where l.id in :ids order by l.id")
    List<PropertyLot> lockByIds(Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from PropertyLot l where l.id = :id")
    Optional<PropertyLot> lockById(Long id);

    @Query("""
            select l from PropertyLot l
            where (:projectId is null or l.projectId = :projectId)
              and (:status is null or l.status = :status)
              and (:type is null or l.lotType = :type)
              and (:q is null or lower(l.reference) like lower(concat('%', cast(:q as string), '%'))
                   or lower(l.commune) like lower(concat('%', cast(:q as string), '%')))
            order by l.reference
            """)
    Page<PropertyLot> search(Long projectId, LotStatus status, LotType type, String q, Pageable pageable);

    long countByStatus(LotStatus status);

    long countByProjectId(Long projectId);

    @Query("select l.status, count(l) from PropertyLot l group by l.status")
    List<Object[]> countByStatusGrouped();
}
