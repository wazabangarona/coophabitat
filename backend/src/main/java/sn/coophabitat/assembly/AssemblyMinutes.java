package sn.coophabitat.assembly;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Compte rendu versionné : une version publiée n'est jamais modifiée, elle est remplacée par une nouvelle version. */
@Entity
@Table(name = "assembly_minutes")
@Getter
@Setter
@NoArgsConstructor
public class AssemblyMinutes {

    public enum Status { BROUILLON, PUBLIE, REMPLACE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long assemblyId;
    private int versionNo;

    @Column(columnDefinition = "text")
    private String content;

    @Enumerated(EnumType.STRING)
    private Status status = Status.BROUILLON;

    private Long authorId;
    private Instant createdAt = Instant.now();
    private Instant publishedAt;
    private Long documentId;
}
