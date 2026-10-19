package sn.coophabitat.finance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "expense")
@Getter
@Setter
@NoArgsConstructor
public class Expense {

    public enum Status { BROUILLON, SOUMISE, APPROUVEE, REJETEE, PAYEE, ANNULEE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String reference;

    @Enumerated(EnumType.STRING)
    private ExpenseCategory category;

    private String label;
    private String supplier;
    private BigDecimal amount;
    private LocalDate expenseDate;

    @Enumerated(EnumType.STRING)
    private Status status = Status.BROUILLON;

    private String paymentAccount;
    private Long justificationDocumentId;
    private int requiredApprovals = 1;
    private Long createdBy;
    private Instant createdAt = Instant.now();
    private Instant submittedAt;
    private Instant paidAt;
    private Long paidBy;
    private Long accountingEntryId;
    private String cancelReason;

    @Version
    private long version;

    @Column(name = "is_demo")
    private boolean demo;
}
