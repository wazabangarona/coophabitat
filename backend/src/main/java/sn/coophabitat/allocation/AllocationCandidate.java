package sn.coophabitat.allocation;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "allocation_candidate")
@Getter
@Setter
@NoArgsConstructor
public class AllocationCandidate {

    public enum Eligibility { A_VERIFIER, ELIGIBLE, NON_ELIGIBLE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long campaignId;
    private Long memberId;
    private Instant appliedAt = Instant.now();

    @Enumerated(EnumType.STRING)
    private Eligibility eligibilityStatus = Eligibility.A_VERIFIER;

    private String eligibilityReason;
    private String eligibilitySnapshot;
    private Instant checkedAt;
    private Instant withdrawnAt;
}
