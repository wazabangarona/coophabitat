package sn.coophabitat.accounting;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.time.LocalDate;

/** Écriture comptable : immuable (annotation Hibernate et triggers PostgreSQL). Correction = contre-écriture. */
@Entity
@Immutable
@Table(name = "accounting_entry")
@Getter
@Setter
@NoArgsConstructor
public class AccountingEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String reference;
    private LocalDate entryDate;
    private String label;
    private String sourceType;
    private Long sourceId;
    private Long reversalOfId;
    private boolean simulated;
    private Long createdBy;
    private Instant createdAt = Instant.now();
}
