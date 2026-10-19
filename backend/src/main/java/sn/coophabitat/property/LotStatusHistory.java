package sn.coophabitat.property;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

@Entity
@Immutable
@Table(name = "lot_status_history")
@Getter
@Setter
@NoArgsConstructor
public class LotStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long lotId;

    @Enumerated(EnumType.STRING)
    private LotStatus fromStatus;

    @Enumerated(EnumType.STRING)
    private LotStatus toStatus;

    private String reason;
    private String sourceType;
    private Long sourceId;
    private Long changedBy;
    private Instant changedAt = Instant.now();
}
