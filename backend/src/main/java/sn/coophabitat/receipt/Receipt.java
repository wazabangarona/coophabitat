package sn.coophabitat.receipt;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "receipt")
@Getter
@Setter
@NoArgsConstructor
public class Receipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String receiptNumber;
    private Long transactionId;
    private Long memberId;
    private String verificationCode;
    private Instant issuedAt = Instant.now();
    private Long documentId;
    private boolean simulated;
}
