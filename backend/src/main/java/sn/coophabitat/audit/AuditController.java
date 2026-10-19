package sn.coophabitat.audit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import sn.coophabitat.common.PageResponse;
import sn.coophabitat.config.ClockConfig;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/audit")
@Tag(name = "Audit")
public class AuditController {

    private final AuditEventRepository repository;
    private final AuditService service;

    public AuditController(AuditEventRepository repository, AuditService service) {
        this.repository = repository;
        this.service = service;
    }

    public record AuditItem(Long id, Instant occurredAt, AuditCategory category, String action, Long actorUserId,
                            String actorLabel, String entityType, String entityId, String details, String ipAddress,
                            String hash) {
        static AuditItem of(AuditEvent e) {
            return new AuditItem(e.getId(), e.getOccurredAt(), e.getCategory(), e.getAction(), e.getActorUserId(),
                    e.getActorLabel(), e.getEntityType(), e.getEntityId(), e.getDetails(), e.getIpAddress(), e.getHash());
        }
    }

    @GetMapping
    @PreAuthorize("hasAuthority('AUDIT_READ')")
    public PageResponse<AuditItem> search(@RequestParam(required = false) AuditCategory category,
                                          @RequestParam(required = false) String action,
                                          @RequestParam(required = false) String entityType,
                                          @RequestParam(required = false) String entityId,
                                          @RequestParam(required = false) LocalDate from,
                                          @RequestParam(required = false) LocalDate to,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "50") int size) {
        Specification<AuditEvent> spec = (root, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (category != null) p.add(cb.equal(root.get("category"), category));
            if (action != null && !action.isBlank()) p.add(cb.equal(root.get("action"), action));
            if (entityType != null && !entityType.isBlank()) p.add(cb.equal(root.get("entityType"), entityType));
            if (entityId != null && !entityId.isBlank()) p.add(cb.equal(root.get("entityId"), entityId));
            if (from != null) p.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from.atStartOfDay(ClockConfig.DAKAR).toInstant()));
            if (to != null) p.add(cb.lessThan(root.get("occurredAt"), to.plusDays(1).atStartOfDay(ClockConfig.DAKAR).toInstant()));
            return cb.and(p.toArray(Predicate[]::new));
        };
        return PageResponse.of(repository.findAll(spec, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200),
                Sort.by(Sort.Direction.DESC, "id"))), AuditItem::of);
    }

    @GetMapping("/verify")
    @PreAuthorize("hasAuthority('AUDIT_READ')")
    @Operation(summary = "Vérifie l'intégrité de la chaîne d'empreintes du journal d'audit")
    public AuditService.ChainVerification verify() {
        return service.verifyChain();
    }
}
