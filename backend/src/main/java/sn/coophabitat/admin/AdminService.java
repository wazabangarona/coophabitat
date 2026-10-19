package sn.coophabitat.admin;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.coophabitat.admin.AdminDtos.*;
import sn.coophabitat.audit.AuditCategory;
import sn.coophabitat.audit.AuditService;
import sn.coophabitat.common.BusinessException;
import sn.coophabitat.common.ReferenceGenerator;
import sn.coophabitat.member.Member;
import sn.coophabitat.member.MemberRepository;
import sn.coophabitat.security.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Administration : comptes, rôles, paramètres de la coopérative et demandes relatives aux données personnelles. */
@Service
public class AdminService {

    /** Rôles qui ne peuvent pas être cumulés avec l'administration technique (séparation des pouvoirs). */
    private static final Set<String> NOT_WITH_TECHNICAL = Set.of("PRESIDENT", "TRESORIER", "SECRETAIRE", "AUDITEUR");

    /** Délai de réponse par défaut d'une demande sur les données personnelles (à confirmer au regard de la loi applicable). */
    private static final Duration DATA_REQUEST_DELAY = Duration.ofDays(30);

    private final UserAccountRepository users;
    private final MemberRepository members;
    private final AuthService auth;
    private final PermissionResolver permissions;
    private final CoopSettingsRepository settings;
    private final DataSubjectRequestRepository dataRequests;
    private final DataRetentionPolicyRepository retention;
    private final ReferenceGenerator references;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    public AdminService(UserAccountRepository users, MemberRepository members, AuthService auth,
                        PermissionResolver permissions, CoopSettingsRepository settings,
                        DataSubjectRequestRepository dataRequests, DataRetentionPolicyRepository retention,
                        ReferenceGenerator references, JdbcTemplate jdbc, AuditService audit, Clock clock) {
        this.users = users;
        this.members = members;
        this.auth = auth;
        this.permissions = permissions;
        this.settings = settings;
        this.dataRequests = dataRequests;
        this.retention = retention;
        this.references = references;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- comptes

    @Transactional(readOnly = true)
    public Page<UserAccount> searchUsers(String q, Pageable p) {
        return users.search(q == null || q.isBlank() ? null : q.trim(), p);
    }

    @Transactional
    public UserAccount createUser(UserCreateRequest r, CurrentUser actor) {
        validateRoles(r.roles());
        if (users.findByEmailIgnoreCase(r.email()).isPresent()) {
            throw BusinessException.conflict("EMAIL_UTILISE", "Un compte existe déjà avec cette adresse");
        }
        if (r.memberId() != null) {
            Member m = members.findById(r.memberId()).orElseThrow(() -> BusinessException.notFound("Membre"));
            if (users.findByMemberId(m.getId()).isPresent()) {
                throw BusinessException.conflict("MEMBRE_DEJA_LIE", "Ce membre a déjà un compte");
            }
        }
        UserAccount u = new UserAccount();
        u.setEmail(r.email().trim().toLowerCase(Locale.ROOT));
        u.setDisplayName(r.displayName().trim());
        u.setMemberId(r.memberId());
        u.setRoles(new HashSet<>(r.roles()));
        u = users.save(u);
        auth.invite(u);
        audit.record(AuditCategory.SECURITE, "COMPTE_CREE", "UserAccount", u.getId(),
                Map.of("roles", String.join(",", new TreeSet<>(r.roles())), "membre", String.valueOf(r.memberId())));
        return u;
    }

    @Transactional
    public UserAccount setRoles(Long userId, Set<String> roles, CurrentUser actor) {
        if (userId.equals(actor.userId())) {
            throw BusinessException.forbidden("Vous ne pouvez pas modifier vos propres rôles");
        }
        validateRoles(roles);
        UserAccount u = users.findById(userId).orElseThrow(() -> BusinessException.notFound("Compte"));
        Set<String> before = new TreeSet<>(u.getRoles());
        u.getRoles().clear();
        u.getRoles().addAll(roles);
        u.setTokenVersion(u.getTokenVersion() + 1);
        audit.record(AuditCategory.SECURITE, "ROLES_MODIFIES", "UserAccount", userId,
                Map.of("avant", String.join(",", before), "apres", String.join(",", new TreeSet<>(roles))));
        return u;
    }

    @Transactional
    public UserAccount setEnabled(Long userId, boolean enabled, String reason, CurrentUser actor) {
        if (userId.equals(actor.userId())) {
            throw BusinessException.forbidden("Vous ne pouvez pas désactiver votre propre compte");
        }
        UserAccount u = users.findById(userId).orElseThrow(() -> BusinessException.notFound("Compte"));
        u.setEnabled(enabled);
        if (!enabled) {
            auth.revokeAllSessions(userId, "COMPTE_DESACTIVE");
        }
        audit.record(AuditCategory.SECURITE, enabled ? "COMPTE_ACTIVE" : "COMPTE_DESACTIVE", "UserAccount", userId,
                Map.of("motif", reason));
        return u;
    }

    @Transactional
    public void resendInvitation(Long userId) {
        UserAccount u = users.findById(userId).orElseThrow(() -> BusinessException.notFound("Compte"));
        if (u.getPasswordHash() != null) {
            throw BusinessException.rule("DEJA_ACTIVE", "Ce compte est déjà activé ; utilisez la réinitialisation du mot de passe");
        }
        auth.invite(u);
        audit.record(AuditCategory.SECURITE, "INVITATION_RENVOYEE", "UserAccount", userId, Map.of());
    }

    @Transactional
    public void unlock(Long userId) {
        UserAccount u = users.findById(userId).orElseThrow(() -> BusinessException.notFound("Compte"));
        u.setFailedAttempts(0);
        u.setLockedUntil(null);
        audit.record(AuditCategory.SECURITE, "COMPTE_DEVERROUILLE", "UserAccount", userId, Map.of());
    }

    @Transactional(readOnly = true)
    public List<RoleResponse> roles() {
        return jdbc.query("SELECT code, label, description, technical FROM role ORDER BY code", (rs, i) -> {
            String code = rs.getString("code");
            return new RoleResponse(code, rs.getString("label"), rs.getString("description"), rs.getBoolean("technical"),
                    permissions.permissionsFor(Set.of(code)).stream().sorted().toList());
        });
    }

    public UserResponse toResponse(UserAccount u) {
        return new UserResponse(u.getId(), u.getEmail(), u.getDisplayName(), u.isEnabled(), u.getPasswordHash() != null,
                new TreeSet<>(u.getRoles()), u.getMemberId(), u.getLastLoginAt(), u.getLockedUntil(), u.isDemo(), u.getCreatedAt());
    }

    private void validateRoles(Set<String> roles) {
        for (String r : roles) {
            try {
                RoleCode.valueOf(r);
            } catch (IllegalArgumentException e) {
                throw BusinessException.badRequest("ROLE_INCONNU", "Rôle inconnu : " + r);
            }
        }
        if (roles.contains("ADMINISTRATEUR_TECHNIQUE") && roles.stream().anyMatch(NOT_WITH_TECHNICAL::contains)) {
            throw BusinessException.rule("CUMUL_INTERDIT",
                    "L'administration technique ne se cumule pas avec un rôle du bureau ou d'audit");
        }
        if (roles.contains("TRESORIER") && roles.contains("PRESIDENT")) {
            throw BusinessException.rule("CUMUL_INTERDIT", "Les rôles de président et de trésorier ne se cumulent pas");
        }
    }

    // ---------------------------------------------------------------- paramètres

    @Transactional(readOnly = true)
    public CoopSettings settings() {
        return settings.current();
    }

    /** Le statut de séquestre n'est pas modifiable par l'API : il exige une intégration bancaire réelle documentée. */
    @Transactional
    public CoopSettings updateSettings(SettingsRequest r) {
        CoopSettings s = settings.current();
        s.setName(r.name());
        s.setLegalForm(r.legalForm());
        s.setRegistrationNumber(r.registrationNumber());
        s.setAddress(r.address());
        s.setCity(r.city());
        s.setPhone(r.phone());
        s.setEmail(r.email());
        s.setPublishPublicStats(r.publishPublicStats());
        s.setUpdatedAt(Instant.now(clock));
        settings.save(s);
        audit.record(AuditCategory.ADMIN, "PARAMETRES_MODIFIES", "CoopSettings", 1L,
                Map.of("statistiques_publiques", r.publishPublicStats()));
        return s;
    }

    @Transactional(readOnly = true)
    public List<DataRetentionPolicy> retentionPolicies() {
        return retention.findAll(org.springframework.data.domain.Sort.by("dataCategory"));
    }

    // ---------------------------------------------------------------- données personnelles

    @Transactional
    public DataSubjectRequest fileDataRequest(Long memberId, DataRequestCreate r) {
        DataSubjectRequest d = new DataSubjectRequest();
        d.setReference(references.next("DDP"));
        d.setMemberId(memberId);
        d.setRequestType(r.type());
        d.setDetails(r.details());
        d.setCreatedAt(Instant.now(clock));
        d.setDueAt(Instant.now(clock).plus(DATA_REQUEST_DELAY));
        d = dataRequests.save(d);
        audit.record(AuditCategory.DONNEES, "DEMANDE_DONNEES_RECUE", "DataSubjectRequest", d.getId(),
                Map.of("type", r.type().name()));
        return d;
    }

    @Transactional
    public DataSubjectRequest decideDataRequest(Long id, DataRequestDecision r, CurrentUser actor) {
        DataSubjectRequest d = dataRequests.findById(id).orElseThrow(() -> BusinessException.notFound("Demande"));
        if (d.getStatus() == DataSubjectRequest.Status.TRAITEE || d.getStatus() == DataSubjectRequest.Status.REJETEE) {
            throw BusinessException.conflict("DEJA_TRAITEE", "Demande déjà close");
        }
        if (actor.memberId() != null && actor.memberId().equals(d.getMemberId())) {
            throw BusinessException.forbidden("Vous ne pouvez pas traiter votre propre demande");
        }
        if (r.status() == DataSubjectRequest.Status.RECUE) {
            throw BusinessException.badRequest("STATUT", "Statut invalide");
        }
        d.setStatus(r.status());
        d.setResponse(r.response());
        d.setHandledBy(actor.userId());
        d.setHandledAt(Instant.now(clock));
        audit.record(AuditCategory.DONNEES, "DEMANDE_DONNEES_" + r.status().name(), "DataSubjectRequest", id, Map.of());
        return d;
    }

    @Transactional(readOnly = true)
    public List<DataRequestResponse> dataRequests(Long memberId) {
        List<DataSubjectRequest> list = memberId == null ? dataRequests.findAllByOrderByCreatedAtDesc()
                : dataRequests.findByMemberIdOrderByCreatedAtDesc(memberId);
        return list.stream().map(d -> new DataRequestResponse(d.getId(), d.getReference(), d.getMemberId(),
                members.findById(d.getMemberId()).map(Member::getMemberNumber).orElse(null), d.getRequestType(), d.getDetails(),
                d.getStatus(), d.getResponse(), d.getCreatedAt(), d.getDueAt(), d.getHandledAt())).toList();
    }
}
