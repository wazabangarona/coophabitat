package sn.coophabitat.payment;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** Paiement effectivement reçu (une seule transaction par intention : contrainte d'unicité). */
@Entity
@Table(name = "payment_transaction")
@Getter
@Setter
@NoArgsConstructor
public class PaymentTransaction {

    public enum Status { CONFIRME, REMBOURSE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String reference;
    private Long intentId;
    private Long memberId;
    private BigDecimal amount;
    private String currency = "XOF";

    @Enumerated(EnumType.STRING)
    private ProviderCode provider;

    private String providerTransactionId;
    private boolean simulated;

    @Enumerated(EnumType.STRING)
    private Status status = Status.CONFIRME;

    private Instant confirmedAt;
    /** WEBHOOK_SIGNE, VERIFICATION_FOURNISSEUR, VALIDATION_MANUELLE, SIMULATION. */
    private String confirmationSource;
    private Instant refundedAt;
    private String refundReason;
    private Long refundedBy;
    private Instant createdAt = Instant.now();

    @Version
    private long version;
}
