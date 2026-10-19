package sn.coophabitat.document;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "document")
@Getter
@Setter
@NoArgsConstructor
public class StoredDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String reference;
    private String title;

    @Enumerated(EnumType.STRING)
    private DocumentCategory category;

    @Enumerated(EnumType.STRING)
    private DocumentVisibility visibility;

    private Long ownerMemberId;
    private String relatedType;
    private Long relatedId;
    private String storageKey;
    private String originalFilename;
    private String contentType;
    private long sizeBytes;
    private String sha256;
    private boolean generated;
    private Instant publishedAt;
    private Long uploadedBy;
    private Instant createdAt = Instant.now();
    private Instant archivedAt;
    @Column(name = "is_demo")
    private boolean demo;
}
