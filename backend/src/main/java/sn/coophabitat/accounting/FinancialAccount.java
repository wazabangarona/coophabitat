package sn.coophabitat.accounting;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "financial_account")
@Getter
@Setter
@NoArgsConstructor
public class FinancialAccount {

    @Id
    private String code;
    private String label;
    private String accountType;
    /** Compte de trésorerie réelle (exclut le compte de simulation). */
    private boolean treasury;
    private boolean active;
}
