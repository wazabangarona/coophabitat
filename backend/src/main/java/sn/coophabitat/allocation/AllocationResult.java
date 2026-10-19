package sn.coophabitat.allocation;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "allocation_result")
@Getter
@Setter
@NoArgsConstructor
public class AllocationResult {

    public enum Status { PROVISOIRE, DEFINITIF, LISTE_ATTENTE, ANNULE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long campaignId;
    private Long candidateId;
    private Long lotId;
    private Integer drawRank;
    private String justification;

    @Enumerated(EnumType.STRING)
    private Status status;

    private String cancelReason;
    private Long decidedBy;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
