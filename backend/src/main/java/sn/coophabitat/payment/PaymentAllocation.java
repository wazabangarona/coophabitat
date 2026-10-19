package sn.coophabitat.payment;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;

/** Imputation d'un paiement sur une échéance. Ajout seul ; une contre-passation est une ligne négative. */
@Entity
@Immutable
@Table(name = "payment_allocation")
@Getter
@Setter
@NoArgsConstructor
public class PaymentAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long transactionId;
    private Long obligationId;
    private BigDecimal amount;
    private Long reversalOfId;
    private Instant createdAt = Instant.now();
}
