package sn.coophabitat.security;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now, t.revokeReason = :reason where t.familyId = :family and t.revokedAt is null")
    int revokeFamily(UUID family, Instant now, String reason);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now, t.revokeReason = :reason where t.userId = :userId and t.revokedAt is null")
    int revokeAllForUser(Long userId, Instant now, String reason);

    @Query("select t from RefreshToken t where t.userId = :userId and t.revokedAt is null and t.expiresAt > :now order by t.createdAt desc")
    List<RefreshToken> findActiveForUser(Long userId, Instant now);
}
