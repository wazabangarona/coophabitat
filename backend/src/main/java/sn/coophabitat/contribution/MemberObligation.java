package sn.coophabitat.contribution;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Échéance due par un membre (cotisation ou pénalité). Les règles de pénalité sont copiées
 * depuis le plan au moment de la génération : modifier un plan ne réécrit pas l'historique.
 */
@Entity
@Table(name = "member_obligation")
@Getter
@Setter
@NoArgsConstructor
public class MemberObligation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long memberId;
    private Long planId;

    @Enumerated(EnumType.STRING)
    private ObligationKind kind;

    private Long parentId;
    private Integer penaltySequence;
    private String periodLabel;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private LocalDate dueDate;
    private BigDecimal amountDue;
    private BigDecimal amountPaid = BigDecimal.ZERO;
    private int graceDays;

    @Enumerated(EnumType.STRING)
    private PenaltyMethod penaltyMethod;

    private BigDecimal penaltyValue;
    private BigDecimal penaltyCap;

    @Enumerated(EnumType.STRING)
    private RoundingMode roundingMode;

    @Enumerated(EnumType.STRING)
    private ObligationStatus status = ObligationStatus.A_PAYER;

    private String cancelReason;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    public BigDecimal outstanding() {
        if (status == ObligationStatus.ANNULEE) {
            return BigDecimal.ZERO;
        }
        return amountDue.subtract(amountPaid);
    }

    /** Recalcule le statut à partir des montants (jamais saisi manuellement). */
    public void refreshStatus() {
        if (status == ObligationStatus.ANNULEE) {
            return;
        }
        if (amountPaid.signum() == 0) {
            status = amountDue.signum() == 0 ? ObligationStatus.PAYEE : ObligationStatus.A_PAYER;
        } else if (amountPaid.compareTo(amountDue) >= 0) {
            status = ObligationStatus.PAYEE;
        } else {
            status = ObligationStatus.PARTIELLE;
        }
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
