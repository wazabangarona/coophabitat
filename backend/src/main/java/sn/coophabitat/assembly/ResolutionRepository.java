package sn.coophabitat.assembly;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ResolutionRepository extends JpaRepository<Resolution, Long> {

    List<Resolution> findByAssemblyIdOrderByIdAsc(Long assemblyId);

    boolean existsByAssemblyIdAndNumber(Long assemblyId, String number);
}
