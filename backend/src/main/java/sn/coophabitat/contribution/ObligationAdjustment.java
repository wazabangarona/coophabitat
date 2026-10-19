package sn.coophabitat.contribution;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "obligation_adjustment")
@Getter
@Setter
@NoArgsConstructor
public class ObligationAdjustment {

    public enum Type { REMISE_PENALITE, ANNULATION, CORRECTION_MONTANT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long obligationId;

    @Enumerated(EnumType.STRING)
    private Type adjustmentType;

    private BigDecimal previousAmount;
    private BigDecimal newAmount;
    private String reason;
    private String decisionReference;
    private Long performedBy;
    private Instant performedAt = Instant.now();
}
