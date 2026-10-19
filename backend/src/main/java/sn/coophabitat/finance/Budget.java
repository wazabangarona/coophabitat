package sn.coophabitat.finance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "budget")
@Getter
@Setter
@NoArgsConstructor
public class Budget {

    public enum Status { BROUILLON, ADOPTE, CLOTURE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private int fiscalYear;
    private String label;

    @Enumerated(EnumType.STRING)
    private Status status = Status.BROUILLON;

    private String adoptedReference;
    private Long createdBy;
    private Instant createdAt = Instant.now();

    @Version
    private long version;
}
