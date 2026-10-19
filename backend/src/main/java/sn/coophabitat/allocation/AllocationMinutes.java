package sn.coophabitat.allocation;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "allocation_minutes")
@Getter
@Setter
@NoArgsConstructor
public class AllocationMinutes {

    public enum Status { BROUILLON, VALIDE, REMPLACE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long campaignId;
    private int versionNo;
    private Long documentId;

    @Enumerated(EnumType.STRING)
    private Status status;

    private Long generatedBy;
    private Instant generatedAt = Instant.now();
    private Instant validatedAt;
}
