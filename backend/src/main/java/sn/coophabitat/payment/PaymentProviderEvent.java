package sn.coophabitat.payment;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "payment_provider_event")
@Getter
@Setter
@NoArgsConstructor
public class PaymentProviderEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private ProviderCode provider;

    private String eventId;
    private String eventType;
    private boolean signatureValid;
    private String payload;
    private Instant receivedAt = Instant.now();
    private Instant processedAt;
    private String processingResult;
    private Long intentId;
}
