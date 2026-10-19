package sn.coophabitat.finance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Solde déclaré par un responsable ou lu sur un relevé : il reste distinct du solde comptable. */
@Entity
@Table(name = "bank_balance_declaration")
@Getter
@Setter
@NoArgsConstructor
public class BankBalanceDeclaration {

    public enum Source { DECLARATION, RELEVE_IMPORTE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String accountCode;
    private LocalDate balanceDate;
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    private Source source;

    private String statementReference;
    private Long declaredBy;
    private Instant declaredAt = Instant.now();
}
