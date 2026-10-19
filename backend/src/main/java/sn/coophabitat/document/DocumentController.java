package sn.coophabitat.document;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import sn.coophabitat.common.Downloads;
import sn.coophabitat.common.PageResponse;
import sn.coophabitat.security.CurrentUserProvider;

import java.time.Instant;
import java.util.List;

@RestController
@Tag(name = "Documents")
public class DocumentController {

    private final DocumentService service;
    private final DocumentRepository repository;

    public DocumentController(DocumentService service, DocumentRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    public record DocumentItem(Long id, String reference, String title, DocumentCategory category,
                               DocumentVisibility visibility, Long ownerMemberId, String relatedType, Long relatedId,
                               String originalFilename, String contentType, long sizeBytes, String sha256,
                               boolean generated, Instant publishedAt, Instant createdAt, Instant archivedAt,
                               boolean demo) {
        public static DocumentItem of(StoredDocument d) {
            return new DocumentItem(d.getId(), d.getReference(), d.getTitle(), d.getCategory(), d.getVisibility(),
                    d.getOwnerMemberId(), d.getRelatedType(), d.getRelatedId(), d.getOriginalFilename(),
                    d.getContentType(), d.getSizeBytes(), d.getSha256(), d.isGenerated(), d.getPublishedAt(),
                    d.getCreatedAt(), d.getArchivedAt(), d.isDemo());
        }
    }

    public record PublishRequest(DocumentVisibility visibility) {
    }

    public record ArchiveRequest(@NotBlank @Size(max = 500) String reason) {
    }

    @GetMapping("/api/v1/documents")
    @Operation(summary = "Documents visibles par l'utilisateur connecté")
    public PageResponse<DocumentItem> list(@RequestParam(required = false) DocumentCategory category,
                                           @RequestParam(required = false) DocumentVisibility visibility,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(service.listForUser(CurrentUserProvider.require(), category, visibility,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100))), DocumentItem::of);
    }

    @GetMapping("/api/v1/documents/{id}")
    public DocumentItem get(@PathVariable Long id) {
        StoredDocument d = service.get(id);
        if (!service.canRead(d, CurrentUserProvider.require())) {
            throw sn.coophabitat.common.BusinessException.notFound("Document");
        }
        return DocumentItem.of(d);
    }

    @GetMapping("/api/v1/documents/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable Long id) {
        var c = service.download(id, CurrentUserProvider.require());
        return Downloads.file(c.bytes(), c.document().getOriginalFilename(),
                MediaType.parseMediaType(c.document().getContentType()), true);
    }

    @PostMapping(value = "/api/v1/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('DOCUMENT_MANAGE')")
    @Operation(summary = "Téléversement (PDF, PNG, JPEG ; type vérifié par signature binaire)")
    public DocumentItem upload(@RequestPart("file") MultipartFile file, @RequestParam String title,
                               @RequestParam DocumentCategory category, @RequestParam DocumentVisibility visibility,
                               @RequestParam(required = false) Long ownerMemberId,
                               @RequestParam(required = false) String relatedType,
                               @RequestParam(required = false) Long relatedId) {
        if (title.isBlank() || title.length() > 300) {
            throw sn.coophabitat.common.BusinessException.badRequest("TITRE_INVALIDE", "Titre obligatoire (300 caractères max.)");
        }
        return DocumentItem.of(service.upload(file, title.trim(), category, visibility, ownerMemberId, relatedType, relatedId));
    }

    @PostMapping("/api/v1/documents/{id}/publish")
    @PreAuthorize("hasAuthority('DOCUMENT_MANAGE')")
    public DocumentItem publish(@PathVariable Long id, @RequestBody(required = false) PublishRequest body) {
        return DocumentItem.of(service.publish(id, body == null ? null : body.visibility()));
    }

    @PostMapping("/api/v1/documents/{id}/archive")
    @PreAuthorize("hasAuthority('DOCUMENT_MANAGE')")
    public DocumentItem archive(@PathVariable Long id, @RequestBody ArchiveRequest body) {
        return DocumentItem.of(service.archive(id, body.reason()));
    }

    @GetMapping("/api/v1/public/documents")
    @Operation(summary = "Documents publics publiés (sans authentification)")
    public List<DocumentItem> publicDocuments() {
        return repository.findPublic().stream().map(DocumentItem::of).toList();
    }

    @GetMapping("/api/v1/public/documents/{id}/content")
    public ResponseEntity<byte[]> publicContent(@PathVariable Long id) {
        var c = service.download(id, null);
        return Downloads.file(c.bytes(), c.document().getOriginalFilename(),
                MediaType.parseMediaType(c.document().getContentType()), true);
    }
}
