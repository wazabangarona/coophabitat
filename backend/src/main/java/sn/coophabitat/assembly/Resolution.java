package sn.coophabitat.assembly;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Résolution adoptée ou rejetée en séance. Les décomptes sont saisis par le secrétariat à partir du vote
 * tenu en séance : l'application n'organise pas de vote électronique.
 */
@Entity
@Table(name = "resolution")
@Getter
@Setter
@NoArgsConstructor
public class Resolution {

    public enum Outcome { ADOPTEE, REJETEE, AJOURNEE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long assemblyId;
    private Long agendaItemId;
    private String number;
    private String text;
    private Integer votesFor;
    private Integer votesAgainst;
    private Integer abstentions;

    @Enumerated(EnumType.STRING)
    private Outcome outcome;

    private Long recordedBy;
    private Instant recordedAt = Instant.now();
}
