package sn.coophabitat.assembly;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AttendanceRepository extends JpaRepository<Attendance, Attendance.Key> {

    List<Attendance> findByAssemblyId(Long assemblyId);

    long countByAssemblyIdAndStatusIn(Long assemblyId, java.util.Collection<Attendance.Status> statuses);
}
