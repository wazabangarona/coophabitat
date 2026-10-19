package sn.coophabitat.allocation;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "allocation_contest")
@Getter
@Setter
@NoArgsConstructor
public class AllocationContest {

    public enum Status { DEPOSEE, EN_EXAMEN, REJETEE, ACCEPTEE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String reference;
    private Long campaignId;
    private Long memberId;
    private Long resultId;
    private Long lotId;
    private String reason;

    @Enumerated(EnumType.STRING)
    private Status status = Status.DEPOSEE;

    private String decision;
    private Long decidedBy;
    private Instant decidedAt;
    private Instant createdAt = Instant.now();

    @Version
    private long version;
}
