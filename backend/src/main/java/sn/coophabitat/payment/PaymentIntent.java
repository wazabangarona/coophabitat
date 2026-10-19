package sn.coophabitat.payment;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payment_intent")
@Getter
@Setter
@NoArgsConstructor
public class PaymentIntent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String reference;
    private Long memberId;
    private Long obligationId;
    private BigDecimal amount;
    private String currency = "XOF";

    @Enumerated(EnumType.STRING)
    private ProviderCode provider;

    /** Moyen précis : WAVE, ESPECES, VIREMENT, CHEQUE, SIMULATION… */
    private String channel;

    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    private String idempotencyKey;
    private String providerSessionId;
    private String providerLaunchUrl;
    private String externalReference;
    private boolean simulated;
    private String failureReason;
    private Long createdBy;
    private Long verifiedBy;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
    private Instant expiresAt;
    private Instant confirmedAt;
    private Instant lastCheckedAt;
    private int checkAttempts;

    @Version
    private long version;

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
