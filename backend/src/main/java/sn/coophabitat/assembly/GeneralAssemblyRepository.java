package sn.coophabitat.assembly;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface GeneralAssemblyRepository extends JpaRepository<GeneralAssembly, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from GeneralAssembly a where a.id = :id")
    Optional<GeneralAssembly> lockById(Long id);

    List<GeneralAssembly> findAllByOrderByScheduledAtDesc();

    Optional<GeneralAssembly> findFirstByStatusInAndScheduledAtAfterOrderByScheduledAtAsc(
            Collection<GeneralAssembly.Status> statuses, java.time.Instant after);

    List<GeneralAssembly> findByStatusInOrderByScheduledAtDesc(Collection<GeneralAssembly.Status> statuses);
}
