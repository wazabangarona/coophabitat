package sn.coophabitat.document;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import sn.coophabitat.audit.AuditCategory;
import sn.coophabitat.audit.AuditService;
import sn.coophabitat.common.BusinessException;
import sn.coophabitat.common.Hashing;
import sn.coophabitat.common.ReferenceGenerator;
import sn.coophabitat.config.AppProperties;
import sn.coophabitat.security.CurrentUser;
import sn.coophabitat.security.CurrentUserProvider;
import sn.coophabitat.security.Permission;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;

@Service
public class DocumentService {

    private final DocumentRepository repository;
    private final FileStorage storage;
    private final ReferenceGenerator references;
    private final AuditService audit;
    private final AppProperties props;
    private final Clock clock;

    public DocumentService(DocumentRepository repository, FileStorage storage, ReferenceGenerator references,
                           AuditService audit, AppProperties props, Clock clock) {
        this.repository = repository;
        this.storage = storage;
        this.references = references;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    public record NewDocument(String title, DocumentCategory category, DocumentVisibility visibility,
                              Long ownerMemberId, String relatedType, Long relatedId, String filename,
                              String contentType, byte[] content, boolean generated, boolean publish) {
    }

    public record Content(StoredDocument document, byte[] bytes) {
    }

    @Transactional
    public StoredDocument store(NewDocument n) {
        if (n.visibility() == DocumentVisibility.PRIVE_MEMBRE && n.ownerMemberId() == null) {
            throw BusinessException.badRequest("PROPRIETAIRE_REQUIS", "Un document privé doit être rattaché à un membre");
        }
        StoredDocument d = new StoredDocument();
        d.setReference(references.next("DOC"));
        d.setTitle(n.title());
        d.setCategory(n.category());
        d.setVisibility(n.visibility());
        d.setOwnerMemberId(n.ownerMemberId());
        d.setRelatedType(n.relatedType());
        d.setRelatedId(n.relatedId());
        d.setOriginalFilename(n.filename());
        d.setContentType(n.contentType());
        d.setSizeBytes(n.content().length);
        d.setSha256(Hashing.sha256Hex(n.content()));
        d.setStorageKey(storage.store(n.content()));
        d.setGenerated(n.generated());
        d.setUploadedBy(CurrentUserProvider.current().map(CurrentUser::userId).orElse(null));
        if (n.publish()) {
            d.setPublishedAt(Instant.now(clock));
        }
        d = repository.save(d);
        audit.record(AuditCategory.DOCUMENT, n.generated() ? "DOCUMENT_GENERE" : "DOCUMENT_TELEVERSE", "Document",
                d.getId(), Map.of("reference", d.getReference(), "categorie", d.getCategory().name(),
                        "visibilite", d.getVisibility().name(), "sha256", d.getSha256()));
        return d;
    }

    /** Téléversement contrôlé : taille, type réel déterminé par signature binaire (et non par l'extension). */
    @Transactional
    public StoredDocument upload(MultipartFile file, String title, DocumentCategory category,
                                 DocumentVisibility visibility, Long ownerMemberId, String relatedType, Long relatedId) {
        if (file == null || file.isEmpty()) {
            throw BusinessException.badRequest("FICHIER_VIDE", "Aucun fichier reçu");
        }
        if (file.getSize() > props.storage().maxUploadBytes()) {
            throw BusinessException.badRequest("FICHIER_TROP_VOLUMINEUX", "Le fichier dépasse la taille autorisée");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw BusinessException.badRequest("FICHIER_ILLISIBLE", "Fichier illisible");
        }
        String type = detectType(bytes);
        if (type == null) {
            throw BusinessException.badRequest("TYPE_NON_AUTORISE", "Seuls les fichiers PDF, PNG et JPEG sont acceptés");
        }
        String name = sanitizeFilename(file.getOriginalFilename(), type);
        return store(new NewDocument(title, category, visibility, ownerMemberId, relatedType, relatedId, name, type,
                bytes, false, false));
    }

    static String detectType(byte[] b) {
        if (b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') {
            return "application/pdf";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        return null;
    }

    static String sanitizeFilename(String original, String type) {
        String base = original == null ? "document" : original.replaceAll("[\\\\/]", "_");
        base = base.replaceAll("[^A-Za-z0-9._ -]", "_");
        if (base.length() > 120) {
            base = base.substring(0, 120);
        }
        String ext = switch (type) {
            case "application/pdf" -> ".pdf";
            case "image/png" -> ".png";
            default -> ".jpg";
        };
        return base.toLowerCase().endsWith(ext) ? base : base + ext;
    }

    @Transactional(readOnly = true)
    public StoredDocument get(Long id) {
        return repository.findById(id).orElseThrow(() -> BusinessException.notFound("Document"));
    }

    /** Règle d'accès unique, appliquée à chaque téléchargement. */
    public boolean canRead(StoredDocument d, CurrentUser user) {
        boolean bureau = user != null && user.has(Permission.DOCUMENT_READ_BUREAU);
        if (bureau) {
            return true;
        }
        if (d.getArchivedAt() != null) {
            return false;
        }
        return switch (d.getVisibility()) {
            case PUBLIC -> d.getPublishedAt() != null;
            case MEMBRES -> user != null && d.getPublishedAt() != null;
            case BUREAU -> false;
            case PRIVE_MEMBRE -> user != null && user.isMember(d.getOwnerMemberId());
        };
    }

    @Transactional
    public Content download(Long id, CurrentUser user) {
        StoredDocument d = get(id);
        if (!canRead(d, user)) {
            // Même réponse que pour un document inexistant : ne révèle pas son existence.
            throw BusinessException.notFound("Document");
        }
        byte[] bytes = storage.read(d.getStorageKey());
        if (!Hashing.sha256Hex(bytes).equals(d.getSha256())) {
            audit.record(AuditCategory.DOCUMENT, "INTEGRITE_DOCUMENT_ROMPUE", "Document", d.getId(), Map.of());
            throw BusinessException.conflict("DOCUMENT_ALTERE", "Le fichier ne correspond plus à son empreinte d'archivage");
        }
        if (d.getVisibility() != DocumentVisibility.PUBLIC) {
            audit.record(AuditCategory.DOCUMENT, "DOCUMENT_CONSULTE", "Document", d.getId(), Map.of());
        }
        return new Content(d, bytes);
    }

    @Transactional
    public StoredDocument publish(Long id, DocumentVisibility visibility) {
        StoredDocument d = get(id);
        if (d.getVisibility() == DocumentVisibility.PRIVE_MEMBRE || visibility == DocumentVisibility.PRIVE_MEMBRE) {
            throw BusinessException.rule("PUBLICATION_INTERDITE", "Un document personnel ne peut pas être publié");
        }
        if (visibility != null) {
            d.setVisibility(visibility);
        }
        d.setPublishedAt(Instant.now(clock));
        audit.record(AuditCategory.DOCUMENT, "DOCUMENT_PUBLIE", "Document", id, Map.of("visibilite", d.getVisibility().name()));
        return d;
    }

    @Transactional
    public StoredDocument archive(Long id, String reason) {
        StoredDocument d = get(id);
        d.setArchivedAt(Instant.now(clock));
        audit.record(AuditCategory.DOCUMENT, "DOCUMENT_ARCHIVE", "Document", id, Map.of("motif", String.valueOf(reason)));
        return d;
    }

    @Transactional(readOnly = true)
    public Page<StoredDocument> listForUser(CurrentUser user, DocumentCategory category, DocumentVisibility visibility,
                                            Pageable pageable) {
        if (user.has(Permission.DOCUMENT_READ_BUREAU)) {
            return repository.searchAll(category, visibility, false, pageable);
        }
        return repository.findVisibleToMember(EnumSet.of(DocumentVisibility.PUBLIC, DocumentVisibility.MEMBRES),
                user.memberId(), category, pageable);
    }
}
