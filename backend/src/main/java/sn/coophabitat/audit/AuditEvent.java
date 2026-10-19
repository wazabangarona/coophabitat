package sn.coophabitat.audit;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Événement d'audit, en ajout seul (UPDATE/DELETE refusés par trigger), chaîné par empreinte SHA-256. */
@Entity
@Table(name = "audit_event")
@Getter
@Setter
@NoArgsConstructor
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditCategory category;

    @Column(nullable = false)
    private String action;

    private Long actorUserId;
    private String actorLabel;
    private String entityType;
    private String entityId;
    private String details;
    private String ipAddress;
    private String previousHash;

    @Column(nullable = false)
    private String hash;
}
