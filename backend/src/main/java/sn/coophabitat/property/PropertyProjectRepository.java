package sn.coophabitat.property;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PropertyProjectRepository extends JpaRepository<PropertyProject, Long> {
    Optional<PropertyProject> findByCode(String code);

    List<PropertyProject> findAllByOrderByNameAsc();
}
