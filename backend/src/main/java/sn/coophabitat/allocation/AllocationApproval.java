package sn.coophabitat.allocation;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

@Entity
@Immutable
@Table(name = "allocation_approval")
@Getter
@Setter
@NoArgsConstructor
public class AllocationApproval {

    public enum Step { VALIDATION_LISTE, VALIDATION_PV, DECISION_COMMISSION, ANNULATION_RESULTAT }

    public enum Decision { APPROUVE, REFUSE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long campaignId;

    @Enumerated(EnumType.STRING)
    private Step step;

    private Long approverId;

    @Enumerated(EnumType.STRING)
    private Decision decision;

    private String comment;
    private Long targetId;
    private Instant decidedAt = Instant.now();
}
