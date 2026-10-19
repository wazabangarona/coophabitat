package sn.coophabitat.contribution;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "contribution_plan")
@Getter
@Setter
@NoArgsConstructor
public class ContributionPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String code;
    private String name;
    private String description;
    private BigDecimal amount;
    private String currency = "XOF";

    @Enumerated(EnumType.STRING)
    private Periodicity periodicity;

    private int dueDayOfMonth;
    private LocalDate validFrom;
    private LocalDate validTo;
    private int graceDays;

    @Enumerated(EnumType.STRING)
    private PenaltyMethod penaltyMethod = PenaltyMethod.AUCUNE;

    private BigDecimal penaltyValue = BigDecimal.ZERO;
    private BigDecimal penaltyCap;

    @Enumerated(EnumType.STRING)
    private RoundingMode roundingMode = RoundingMode.HALF_UP;

    @Enumerated(EnumType.STRING)
    private PlanStatus status = PlanStatus.BROUILLON;

    /** Référence de la décision d'adoption (ex. résolution d'AG). */
    private String adoptedReference;
    private Long createdBy;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @Column(name = "is_demo")
    private boolean demo;

    @Version
    private long version;

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
