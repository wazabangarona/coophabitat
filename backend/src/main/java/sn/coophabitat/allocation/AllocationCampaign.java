package sn.coophabitat.allocation;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "allocation_campaign")
@Getter
@Setter
@NoArgsConstructor
public class AllocationCampaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String reference;
    private String title;
    private String description;

    @Enumerated(EnumType.STRING)
    private AllocationMethod method;

    @Enumerated(EnumType.STRING)
    private CampaignStatus status = CampaignStatus.BROUILLON;

    private String eligibilityText;
    private String rulesText;
    private int minSeniorityMonths;
    private boolean requireUpToDate = true;
    private BigDecimal maxArrearsAmount = BigDecimal.ZERO;
    private String allowedMemberTypes = "FONDATEUR,ORDINAIRE,BENEFICIAIRE";
    private LocalDate referenceDate;
    private Instant applicationOpensAt;
    private Instant applicationClosesAt;
    private int requiredApprovals = 2;
    private Instant sessionDate;
    private String sessionLocation;
    private Instant criteriaPublishedAt;
    private Instant listPublishedAt;
    private Instant frozenAt;
    private String participantsDigest;
    private String lotsDigest;
    /** Engagement SHA-256 sur la graine secrète, publié avant le tirage. */
    private String seedCommitment;
    /** Graine secrète : jamais exposée par l'API avant l'exécution du tirage. */
    private String seedSecret;
    private boolean seedRevealed;
    private String publicEntropy;
    private String algorithmVersion;
    private Instant executedAt;
    private Long executedBy;
    private Instant resultsPublishedAt;
    private Instant archivedAt;
    private String cancelReason;
    private Long createdBy;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
    @Column(name = "is_demo")
    private boolean demo;

    @Version
    private long version;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "allocation_campaign_lot", joinColumns = @JoinColumn(name = "campaign_id"))
    @Column(name = "lot_id")
    private Set<Long> lotIds = new LinkedHashSet<>();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
