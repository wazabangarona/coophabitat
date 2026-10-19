package sn.coophabitat.security;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "one_time_token")
@Getter
@Setter
@NoArgsConstructor
public class OneTimeToken {

    public enum Purpose { PASSWORD_RESET, EMAIL_VERIFICATION, INVITATION }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    @Enumerated(EnumType.STRING)
    private Purpose purpose;

    private String tokenHash;
    private Instant expiresAt;
    private Instant usedAt;
    private Instant createdAt = Instant.now();
}
