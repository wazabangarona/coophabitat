package sn.coophabitat.admin;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Demande d'un membre relative à ses données personnelles (accès, rectification, opposition, suppression, portabilité). */
@Entity
@Table(name = "data_subject_request")
@Getter
@Setter
@NoArgsConstructor
public class DataSubjectRequest {

    public enum Type { ACCES, RECTIFICATION, OPPOSITION, SUPPRESSION, PORTABILITE }

    public enum Status { RECUE, EN_COURS, TRAITEE, REJETEE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String reference;
    private Long memberId;

    @Enumerated(EnumType.STRING)
    private Type requestType;

    private String details;

    @Enumerated(EnumType.STRING)
    private Status status = Status.RECUE;

    private String response;
    private Instant createdAt = Instant.now();
    private Instant dueAt;
    private Long handledBy;
    private Instant handledAt;
}
