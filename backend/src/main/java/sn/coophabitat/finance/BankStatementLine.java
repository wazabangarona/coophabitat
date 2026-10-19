package sn.coophabitat.finance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "bank_statement_line")
@Getter
@Setter
@NoArgsConstructor
public class BankStatementLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String accountCode;
    private LocalDate operationDate;
    private String label;
    /** Positif = crédit sur le compte (entrée de fonds), négatif = débit (sortie). */
    private BigDecimal amount;
    private String bankReference;
    private Long matchedEntryId;
    private Long matchedBy;
    private Instant matchedAt;
    private Long importedBy;
    private Instant importedAt = Instant.now();
    private String importHash;
}
