package sn.coophabitat.property;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "property_project")
@Getter
@Setter
@NoArgsConstructor
public class PropertyProject {

    public enum Status { ETUDE, EN_COURS, LIVRE, SUSPENDU }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String code;
    private String name;
    private String description;
    private String region;
    private String departement;
    private String commune;

    @Enumerated(EnumType.STRING)
    private Status status = Status.ETUDE;

    private Instant createdAt = Instant.now();
    @Column(name = "is_demo")
    private boolean demo;

    @Version
    private long version;
}
