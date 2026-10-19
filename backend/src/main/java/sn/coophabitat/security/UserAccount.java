package sn.coophabitat.security;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "user_account")
@Getter
@Setter
@NoArgsConstructor
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String email;

    /** Empreinte Argon2id ; null tant que l'invitation n'est pas acceptée. */
    private String passwordHash;

    @Column(nullable = false)
    private String displayName;

    private boolean enabled = true;
    private boolean emailVerified;
    private int failedAttempts;
    private Instant lockedUntil;
    /** Incrémenté pour invalider immédiatement tous les jetons d'accès émis. */
    private int tokenVersion;
    private Long memberId;
    private Instant lastLoginAt;
    private Instant passwordChangedAt;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
    @Column(name = "is_demo")
    private boolean demo;

    @Version
    private long version;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_role", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role_code")
    private Set<String> roles = new HashSet<>();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
