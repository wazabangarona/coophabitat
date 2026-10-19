package sn.coophabitat.property;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Lot (terrain ou logement). Les coordonnées GPS sont facultatives et doivent provenir d'une source
 * identifiée ; elles n'ont jamais valeur de limites cadastrales.
 */
@Entity
@Table(name = "property_lot")
@Getter
@Setter
@NoArgsConstructor
public class PropertyLot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String reference;
    private Long projectId;

    @Enumerated(EnumType.STRING)
    private LotType lotType;

    private String region;
    private String departement;
    private String commune;
    private String locality;
    private BigDecimal areaM2;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private String coordinatesSource;
    private boolean coordinatesVerified;

    @Enumerated(EnumType.STRING)
    private LotStatus status = LotStatus.EN_PREPARATION;

    private String description;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
    @Column(name = "is_demo")
    private boolean demo;

    @Version
    private long version;

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
