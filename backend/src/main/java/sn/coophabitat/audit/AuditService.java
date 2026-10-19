package sn.coophabitat.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import sn.coophabitat.common.Hashing;
import sn.coophabitat.common.Json;
import sn.coophabitat.security.CurrentUser;
import sn.coophabitat.security.CurrentUserProvider;
import sn.coophabitat.security.ClientInfo;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Journal d'audit chaîné : chaque événement contient l'empreinte du précédent.
 * Une altération directe en base (contournant les triggers) devient détectable par {@link #verifyChain()}.
 * Ce mécanisme prouve l'intégrité du journal, pas à lui seul l'équité juridique des décisions journalisées.
 */
@Service
public class AuditService {

    /** Identifiant arbitraire du verrou consultatif sérialisant l'écriture de la chaîne. */
    private static final long CHAIN_LOCK = 4_242_001L;

    private final AuditEventRepository repository;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AuditService(AuditEventRepository repository, JdbcTemplate jdbc, Clock clock) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public AuditEvent record(AuditCategory category, String action, String entityType, Object entityId,
                             Map<String, ?> details) {
        Optional<CurrentUser> actor = CurrentUserProvider.current();
        return recordAs(category, action, entityType, entityId, details,
                actor.map(CurrentUser::userId).orElse(null), actor.map(CurrentUser::email).orElse("système"));
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public AuditEvent recordAs(AuditCategory category, String action, String entityType, Object entityId,
                               Map<String, ?> details, Long actorUserId, String actorLabel) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(?)", CHAIN_LOCK);
        String previous = repository.findTopByOrderByIdDesc().map(AuditEvent::getHash).orElse(null);
        AuditEvent e = new AuditEvent();
        e.setOccurredAt(Instant.now(clock).truncatedTo(ChronoUnit.MICROS));
        e.setCategory(category);
        e.setAction(action);
        e.setActorUserId(actorUserId);
        e.setActorLabel(actorLabel);
        e.setEntityType(entityType);
        e.setEntityId(entityId == null ? null : String.valueOf(entityId));
        e.setDetails(details == null || details.isEmpty() ? null : Json.write(details));
        e.setIpAddress(currentIp());
        e.setPreviousHash(previous);
        e.setHash(computeHash(e));
        return repository.save(e);
    }

    static String computeHash(AuditEvent e) {
        String payload = String.join("|",
                String.valueOf(e.getPreviousHash()),
                e.getOccurredAt().toString(),
                e.getCategory().name(),
                e.getAction(),
                String.valueOf(e.getActorUserId()),
                String.valueOf(e.getEntityType()),
                String.valueOf(e.getEntityId()),
                String.valueOf(e.getDetails()));
        return Hashing.sha256Hex(payload);
    }

    public record ChainVerification(boolean valid, long checkedEvents, Long firstInvalidEventId, String message) {
    }

    @Transactional(readOnly = true)
    public ChainVerification verifyChain() {
        long checked = 0;
        long afterId = 0;
        String previous = null;
        while (true) {
            List<AuditEvent> chunk = repository.findChunk(afterId, PageRequest.of(0, 500));
            if (chunk.isEmpty()) {
                break;
            }
            for (AuditEvent e : chunk) {
                if (!java.util.Objects.equals(previous, e.getPreviousHash()) || !computeHash(e).equals(e.getHash())) {
                    return new ChainVerification(false, checked, e.getId(),
                            "Rupture de la chaîne d'audit détectée à l'événement " + e.getId());
                }
                previous = e.getHash();
                afterId = e.getId();
                checked++;
            }
        }
        return new ChainVerification(true, checked, null, "Chaîne d'audit intègre");
    }

    private static String currentIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest req = attrs.getRequest();
            return ClientInfo.ip(req);
        }
        return null;
    }
}
