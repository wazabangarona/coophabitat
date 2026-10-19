package sn.coophabitat.member;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Dossier d'adhérent. Seules les données nécessaires à la gestion de l'adhésion sont collectées
 * (pas de numéro de pièce d'identité, pas de date de naissance).
 */
@Entity
@Table(name = "member")
@Getter
@Setter
@NoArgsConstructor
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String memberNumber;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private String address;
    private String city;
    private LocalDate membershipDate;

    @Enumerated(EnumType.STRING)
    private MemberType memberType;

    @Enumerated(EnumType.STRING)
    private MemberStatus status = MemberStatus.EN_ATTENTE;

    private Long contributionPlanId;
    private String notes;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @Column(name = "is_demo")
    private boolean demo;

    @Version
    private long version;

    public String fullName() {
        return firstName + " " + lastName;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
