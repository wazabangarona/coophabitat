package sn.coophabitat.finance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/** Rapport financier : instantané figé (JSON) et PDF associé ; seule la publication change son état. */
@Entity
@Table(name = "financial_report")
@Getter
@Setter
@NoArgsConstructor
public class FinancialReport {

    public enum Type { SEMESTRIEL, ANNUEL, PERIODE }

    public enum Status { BROUILLON, PUBLIE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String reference;

    @Enumerated(EnumType.STRING)
    private Type reportType;

    private LocalDate periodStart;
    private LocalDate periodEnd;

    @Enumerated(EnumType.STRING)
    private Status status = Status.BROUILLON;

    @Column(columnDefinition = "text")
    private String snapshot;

    private Long documentId;
    private Long generatedBy;
    private Instant generatedAt = Instant.now();
    private Long publishedBy;
    private Instant publishedAt;
}
