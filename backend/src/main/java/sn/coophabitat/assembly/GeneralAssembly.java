package sn.coophabitat.assembly;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "general_assembly")
@Getter
@Setter
@NoArgsConstructor
public class GeneralAssembly {

    public enum Type { ORDINAIRE, EXTRAORDINAIRE }

    public enum Modality { PRESENTIEL, EN_LIGNE, MIXTE }

    public enum Status { PROGRAMMEE, CONVOQUEE, TENUE, CLOTUREE, ANNULEE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String reference;
    private String title;

    @Enumerated(EnumType.STRING)
    private Type assemblyType;

    private Instant scheduledAt;
    private String location;

    @Enumerated(EnumType.STRING)
    private Modality modality;

    @Enumerated(EnumType.STRING)
    private Status status = Status.PROGRAMMEE;

    private String quorumRule;
    private String convocationText;
    private Instant convenedAt;
    private Instant heldAt;
    private String cancelReason;
    private Long createdBy;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    @Column(name = "is_demo", insertable = false, updatable = false) // positionné uniquement par le chargeur de démonstration
    private boolean demo;
}
