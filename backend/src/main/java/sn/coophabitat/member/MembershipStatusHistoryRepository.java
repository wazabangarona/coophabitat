package sn.coophabitat.member;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MembershipStatusHistoryRepository extends JpaRepository<MembershipStatusHistory, Long> {
    List<MembershipStatusHistory> findByMemberIdOrderByChangedAtAscIdAsc(Long memberId);
}
