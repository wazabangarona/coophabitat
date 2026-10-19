package sn.coophabitat.finance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "budget_line")
@Getter
@Setter
@NoArgsConstructor
public class BudgetLine {

    public enum Direction { RECETTE, DEPENSE }

    /** Catégories de recettes ; les dépenses utilisent {@link ExpenseCategory}. */
    public static final java.util.List<String> REVENUE_CATEGORIES = java.util.List.of("COTISATIONS", "PENALITES");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long budgetId;
    private String category;

    @Enumerated(EnumType.STRING)
    private Direction direction;

    private BigDecimal plannedAmount;
}
