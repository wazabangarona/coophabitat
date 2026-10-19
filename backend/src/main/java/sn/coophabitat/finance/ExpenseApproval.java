package sn.coophabitat.finance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/** Décision d'approbation : ajout seul (trigger PostgreSQL). */
@Entity
@Immutable
@Table(name = "expense_approval")
@Getter
@Setter
@NoArgsConstructor
public class ExpenseApproval {

    public enum Decision { APPROUVEE, REJETEE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long expenseId;
    private Long approverId;

    @Enumerated(EnumType.STRING)
    private Decision decision;

    private String comment;
    private Instant decidedAt = Instant.now();
}
