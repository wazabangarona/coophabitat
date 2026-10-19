package sn.coophabitat.admin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DataSubjectRequestRepository extends JpaRepository<DataSubjectRequest, Long> {

    List<DataSubjectRequest> findByMemberIdOrderByCreatedAtDesc(Long memberId);

    List<DataSubjectRequest> findAllByOrderByCreatedAtDesc();

    long countByStatusIn(java.util.Collection<DataSubjectRequest.Status> statuses);
}
