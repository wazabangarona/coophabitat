package sn.coophabitat.assembly;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AssemblyMinutesRepository extends JpaRepository<AssemblyMinutes, Long> {

    List<AssemblyMinutes> findByAssemblyIdOrderByVersionNoAsc(Long assemblyId);

    Optional<AssemblyMinutes> findTopByAssemblyIdAndStatusOrderByVersionNoDesc(Long assemblyId, AssemblyMinutes.Status status);
}
