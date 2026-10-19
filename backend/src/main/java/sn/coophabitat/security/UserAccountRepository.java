package sn.coophabitat.security;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    @Query("select u from UserAccount u where lower(u.email) = lower(:email)")
    Optional<UserAccount> findByEmailIgnoreCase(String email);

    Optional<UserAccount> findByMemberId(Long memberId);

    @Query("select u from UserAccount u where (:q is null or lower(u.email) like lower(concat('%', cast(:q as string), '%')) "
            + "or lower(u.displayName) like lower(concat('%', cast(:q as string), '%'))) order by u.displayName")
    Page<UserAccount> search(String q, Pageable pageable);

    @Query("select distinct u from UserAccount u join u.roles r where r in :roles and u.enabled = true")
    List<UserAccount> findActiveByRoles(java.util.Collection<String> roles);
}
