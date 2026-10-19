package sn.coophabitat.security;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Jeton de renouvellement : seule son empreinte SHA-256 est stockée ; rotation à chaque usage. */
@Entity
@Table(name = "refresh_token")
@Getter
@Setter
@NoArgsConstructor
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private String tokenHash;
    private UUID familyId;
    private Instant expiresAt;
    private Instant createdAt = Instant.now();
    private Instant revokedAt;
    private String revokeReason;
    private Long replacedBy;
    private String userAgent;
    private String ipAddress;
}
