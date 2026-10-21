package sn.coophabitat.admin;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "coop_settings")
@Getter
@Setter
@NoArgsConstructor
public class CoopSettings {

    @Id
    private Short id = 1;
    private String name;
    private String legalForm;
    private String registrationNumber;
    private String address;
    private String city;
    private String phone;
    private String email;
    private String escrowStatement;
    /** Reste faux tant qu'aucun mécanisme juridique et bancaire de séquestre n'est réellement intégré. */
    private boolean escrowIntegrated;
    private boolean publishPublicStats;
    /** Où et comment payer la coopérative (numéro Wave ou Orange Money, compte bancaire...), affiché aux membres. */
    private String paymentInstructions;
    @Column(name = "is_demo")
    private boolean demo;
    private Instant updatedAt = Instant.now();
}
