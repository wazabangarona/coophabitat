package sn.coophabitat.member;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {

    /** Verrou sur le dossier : sérialise les opérations financières d'un même membre. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.id = :id")
    Optional<Member> lockById(Long id);

    Optional<Member> findByMemberNumber(String memberNumber);

    @Query("select m from Member m where lower(m.email) = lower(:email)")
    Optional<Member> findByEmailIgnoreCase(String email);

    Optional<Member> findByPhone(String phone);

    @Query("""
            select m from Member m
            where (:q is null or lower(m.lastName) like lower(concat('%', cast(:q as string), '%'))
                   or lower(m.firstName) like lower(concat('%', cast(:q as string), '%'))
                   or lower(m.memberNumber) like lower(concat('%', cast(:q as string), '%'))
                   or lower(m.email) like lower(concat('%', cast(:q as string), '%')))
              and (:status is null or m.status = :status)
              and (:type is null or m.memberType = :type)
            """)
    Page<Member> search(String q, MemberStatus status, MemberType type, Pageable pageable);

    List<Member> findByStatusIn(Collection<MemberStatus> statuses);

    long countByStatus(MemberStatus status);
}
