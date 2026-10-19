package sn.coophabitat.member;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "membership_status_history")
@Getter
@Setter
@NoArgsConstructor
public class MembershipStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long memberId;

    @Enumerated(EnumType.STRING)
    private MemberStatus fromStatus;

    @Enumerated(EnumType.STRING)
    private MemberStatus toStatus;

    private String reason;
    private Long changedBy;
    private Instant changedAt = Instant.now();
}
