package sn.coophabitat.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.coophabitat.audit.AuditCategory;
import sn.coophabitat.audit.AuditService;
import sn.coophabitat.common.BusinessException;
import sn.coophabitat.common.Hashing;
import sn.coophabitat.config.AppProperties;
import sn.coophabitat.notification.NotificationService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    /** Message unique : ne révèle ni l'existence du compte, ni son verrouillage. */
    static final String INVALID_CREDENTIALS = "Identifiants invalides ou compte temporairement indisponible";

    private final UserAccountRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final OneTimeTokenRepository oneTimeTokens;
    private final PasswordEncoder encoder;
    private final PasswordPolicy passwordPolicy;
    private final JwtService jwtService;
    private final PermissionResolver permissions;
    private final AuditService audit;
    private final NotificationService notifications;
    private final RateLimiter rateLimiter;
    private final AppProperties props;
    private final Clock clock;
    private final String dummyHash;

    public AuthService(UserAccountRepository users, RefreshTokenRepository refreshTokens,
                       OneTimeTokenRepository oneTimeTokens, PasswordEncoder encoder, PasswordPolicy passwordPolicy,
                       JwtService jwtService, PermissionResolver permissions, AuditService audit,
                       NotificationService notifications, RateLimiter rateLimiter, AppProperties props, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.oneTimeTokens = oneTimeTokens;
        this.encoder = encoder;
        this.passwordPolicy = passwordPolicy;
        this.jwtService = jwtService;
        this.permissions = permissions;
        this.audit = audit;
        this.notifications = notifications;
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.clock = clock;
        // Empreinte factice : égalise le temps de réponse lorsque le compte n'existe pas.
        this.dummyHash = encoder.encode(Hashing.randomToken());
    }

    public record LoginResult(String accessToken, String refreshToken, CurrentUser user) {
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public LoginResult login(String email, String password, String ip, String userAgent) {
        String normalized = email.trim().toLowerCase();
        if (!rateLimiter.tryAcquire("login:" + normalized, 10, Duration.ofMinutes(15).toMillis())) {
            audit.recordAs(AuditCategory.SECURITE, "CONNEXION_LIMITEE", "UserAccount", null,
                    Map.of("email", normalized), null, normalized);
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "TROP_DE_TENTATIVES",
                    "Trop de tentatives de connexion. Réessayez plus tard.");
        }
        UserAccount user = users.findByEmailIgnoreCase(normalized).orElse(null);
        Instant now = Instant.now(clock);
        if (user == null || user.getPasswordHash() == null) {
            encoder.matches(password, dummyHash);
            audit.recordAs(AuditCategory.SECURITE, "CONNEXION_ECHEC", "UserAccount", null,
                    Map.of("motif", "compte inconnu ou non activé"), null, normalized);
            throw invalidCredentials();
        }
        boolean locked = user.getLockedUntil() != null && user.getLockedUntil().isAfter(now);
        boolean matches = encoder.matches(password, user.getPasswordHash());
        if (locked || !user.isEnabled() || !matches) {
            if (!locked && !matches) {
                user.setFailedAttempts(user.getFailedAttempts() + 1);
                if (user.getFailedAttempts() >= props.security().maxFailedAttempts()) {
                    user.setLockedUntil(now.plus(props.security().lockDuration()));
                    user.setFailedAttempts(0);
                    audit.recordAs(AuditCategory.SECURITE, "COMPTE_VERROUILLE", "UserAccount", user.getId(),
                            Map.of("jusqua", user.getLockedUntil().toString()), user.getId(), user.getEmail());
                }
            }
            audit.recordAs(AuditCategory.SECURITE, "CONNEXION_ECHEC", "UserAccount", user.getId(),
                    Map.of("motif", locked ? "verrouillé" : !user.isEnabled() ? "désactivé" : "mot de passe"),
                    user.getId(), user.getEmail());
            throw invalidCredentials();
        }
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        if (encoder.upgradeEncoding(user.getPasswordHash())) {
            user.setPasswordHash(encoder.encode(password));
        }
        String refresh = createRefreshToken(user.getId(), UUID.randomUUID(), ip, userAgent).raw();
        audit.recordAs(AuditCategory.SECURITE, "CONNEXION_REUSSIE", "UserAccount", user.getId(), Map.of(),
                user.getId(), user.getEmail());
        return new LoginResult(jwtService.issueAccessToken(user), refresh, toCurrentUser(user));
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public LoginResult refresh(String rawToken, String ip, String userAgent) {
        if (rawToken == null || rawToken.isBlank()) {
            throw sessionExpired();
        }
        Instant now = Instant.now(clock);
        RefreshToken token = refreshTokens.findByTokenHash(Hashing.sha256Hex(rawToken)).orElseThrow(this::sessionExpired);
        if (token.getRevokedAt() != null) {
            // Réutilisation d'un jeton déjà consommé : vol probable, toute la famille est révoquée.
            refreshTokens.revokeFamily(token.getFamilyId(), now, "REUTILISATION_DETECTEE");
            audit.recordAs(AuditCategory.SECURITE, "REUTILISATION_JETON", "UserAccount", token.getUserId(),
                    Map.of("famille", token.getFamilyId().toString()), token.getUserId(), null);
            throw sessionExpired();
        }
        if (token.getExpiresAt().isBefore(now)) {
            throw sessionExpired();
        }
        UserAccount user = users.findById(token.getUserId()).orElseThrow(this::sessionExpired);
        if (!user.isEnabled() || (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now))) {
            refreshTokens.revokeFamily(token.getFamilyId(), now, "COMPTE_INDISPONIBLE");
            throw sessionExpired();
        }
        IssuedRefresh next = createRefreshToken(user.getId(), token.getFamilyId(), ip, userAgent);
        token.setRevokedAt(now);
        token.setRevokeReason("ROTATION");
        token.setReplacedBy(next.entity().getId());
        return new LoginResult(jwtService.issueAccessToken(user), next.raw(), toCurrentUser(user));
    }

    @Transactional
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        refreshTokens.findByTokenHash(Hashing.sha256Hex(rawToken)).ifPresent(t -> {
            refreshTokens.revokeFamily(t.getFamilyId(), Instant.now(clock), "DECONNEXION");
            audit.recordAs(AuditCategory.SECURITE, "DECONNEXION", "UserAccount", t.getUserId(), Map.of(),
                    t.getUserId(), null);
        });
    }

    /** Invalide immédiatement tous les jetons d'accès et de renouvellement d'un utilisateur. */
    @Transactional
    public void revokeAllSessions(Long userId, String reason) {
        UserAccount user = users.findById(userId).orElseThrow(() -> BusinessException.notFound("Utilisateur"));
        user.setTokenVersion(user.getTokenVersion() + 1);
        refreshTokens.revokeAllForUser(userId, Instant.now(clock), reason);
        audit.record(AuditCategory.SECURITE, "SESSIONS_REVOQUEES", "UserAccount", userId, Map.of("motif", reason));
    }

    @Transactional
    public void requestPasswordReset(String email) {
        users.findByEmailIgnoreCase(email.trim()).filter(UserAccount::isEnabled).ifPresent(user -> {
            String raw = createOneTimeToken(user.getId(), OneTimeToken.Purpose.PASSWORD_RESET,
                    props.security().passwordResetTtl());
            String link = props.frontendBaseUrl() + "/reinitialisation?jeton=" + raw;
            notifications.sendSecurityEmail(user, "REINITIALISATION_MOT_DE_PASSE",
                    "Réinitialisation de votre mot de passe CoopHabitat",
                    "Bonjour,\n\nUne demande de réinitialisation de mot de passe a été faite pour votre compte. "
                            + "Ce lien est valable " + props.security().passwordResetTtl().toMinutes() + " minutes :\n"
                            + link + "\n\nSi vous n'êtes pas à l'origine de cette demande, ignorez ce message.");
            logDevelopmentLink("réinitialisation", link);
            audit.recordAs(AuditCategory.SECURITE, "REINITIALISATION_DEMANDEE", "UserAccount", user.getId(),
                    Map.of(), user.getId(), user.getEmail());
        });
    }

    @Transactional
    public void confirmPasswordReset(String rawToken, String newPassword) {
        consumeToken(rawToken, OneTimeToken.Purpose.PASSWORD_RESET, newPassword, "MOT_DE_PASSE_REINITIALISE");
    }

    @Transactional
    public void acceptInvitation(String rawToken, String newPassword) {
        consumeToken(rawToken, OneTimeToken.Purpose.INVITATION, newPassword, "INVITATION_ACCEPTEE");
    }

    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        UserAccount user = users.findById(userId).orElseThrow(() -> BusinessException.notFound("Utilisateur"));
        if (user.getPasswordHash() == null || !encoder.matches(currentPassword, user.getPasswordHash())) {
            throw BusinessException.badRequest("MOT_DE_PASSE_ACTUEL_INVALIDE", "Le mot de passe actuel est incorrect");
        }
        setPassword(user, newPassword);
        audit.record(AuditCategory.SECURITE, "MOT_DE_PASSE_MODIFIE", "UserAccount", userId, Map.of());
    }

    /** Crée un jeton d'invitation et envoie le lien d'activation par courriel. */
    @Transactional
    public String invite(UserAccount user) {
        String raw = createOneTimeToken(user.getId(), OneTimeToken.Purpose.INVITATION, props.security().invitationTtl());
        String link = props.frontendBaseUrl() + "/activation?jeton=" + raw;
        notifications.sendSecurityEmail(user, "INVITATION", "Activation de votre compte CoopHabitat",
                "Bonjour " + user.getDisplayName() + ",\n\nUn compte CoopHabitat a été créé pour vous. "
                        + "Choisissez votre mot de passe avec ce lien, valable "
                        + props.security().invitationTtl().toHours() + " heures :\n" + link);
        logDevelopmentLink("activation", link);
        return raw;
    }

    /**
     * Lien d'accès remis par un responsable : envoyé par courriel si un serveur SMTP est configuré, sinon renvoyé à
     * l'appelant pour qu'il le transmette lui-même à la personne (sans SMTP, c'est le seul moyen d'activer un compte).
     */
    public record AccessLink(boolean emailSent, String link, String validity) {
    }

    /** Invitation d'un compte créé par un responsable ; le lien n'est rendu que si aucun courriel ne peut partir. */
    @Transactional
    public AccessLink inviteWithLink(UserAccount user) {
        String raw = invite(user);
        if (notifications.emailAvailable()) {
            return new AccessLink(true, null, props.security().invitationTtl().toHours() + " heures");
        }
        return new AccessLink(false, props.frontendBaseUrl() + "/activation?jeton=" + raw,
                props.security().invitationTtl().toHours() + " heures");
    }

    /**
     * Réinitialisation demandée par l'administrateur pour une personne qui a perdu son mot de passe :
     * courriel à la personne si possible, sinon lien à lui remettre en main propre. La personne est prévenue dans
     * l'application et l'opération est journalisée.
     */
    @Transactional
    public AccessLink passwordResetLinkFor(UserAccount user, CurrentUser actor) {
        if (user.getPasswordHash() == null) {
            throw BusinessException.rule("NON_ACTIVE", "Ce compte n'est pas encore activé : renvoyez plutôt l'invitation");
        }
        if (!user.isEnabled()) {
            throw BusinessException.rule("COMPTE_DESACTIVE", "Ce compte est désactivé : réactivez-le d'abord");
        }
        if (user.getId().equals(actor.userId())) {
            throw BusinessException.forbidden("Changez votre propre mot de passe depuis « Mon compte »");
        }
        String raw = createOneTimeToken(user.getId(), OneTimeToken.Purpose.PASSWORD_RESET,
                props.security().passwordResetTtl());
        String link = props.frontendBaseUrl() + "/reinitialisation?jeton=" + raw;
        String validity = props.security().passwordResetTtl().toMinutes() + " minutes";
        boolean emailed = notifications.emailAvailable();
        if (emailed) {
            notifications.sendSecurityEmail(user, "REINITIALISATION_MOT_DE_PASSE",
                    "Réinitialisation de votre mot de passe CoopHabitat",
                    "Bonjour,\n\nL'administrateur de la coopérative a lancé la réinitialisation de votre mot de passe. "
                            + "Ce lien est valable " + validity + " :\n" + link
                            + "\n\nSi vous n'avez rien demandé, prévenez le bureau.");
        }
        notifications.notifyUser(user.getId(), "REINITIALISATION_PAR_ADMINISTRATEUR",
                "Réinitialisation de votre mot de passe",
                "L'administrateur a créé un lien de réinitialisation de votre mot de passe. Si vous ne l'avez pas demandé, "
                        + "prévenez le bureau.", "REINIT_ADMIN:" + user.getId() + ":" + Hashing.randomCode(8),
                "UserAccount", user.getId());
        logDevelopmentLink("réinitialisation", link);
        audit.record(AuditCategory.SECURITE, "LIEN_REINITIALISATION_GENERE", "UserAccount", user.getId(),
                Map.of("par_courriel", emailed));
        return new AccessLink(emailed, emailed ? null : link, validity);
    }

    @Transactional(readOnly = true)
    public List<RefreshToken> activeSessions(Long userId) {
        return refreshTokens.findActiveForUser(userId, Instant.now(clock));
    }

    public CurrentUser toCurrentUser(UserAccount user) {
        Set<String> roles = Set.copyOf(user.getRoles());
        return new CurrentUser(user.getId(), user.getEmail(), user.getDisplayName(), user.getMemberId(), roles,
                Set.copyOf(permissions.permissionsFor(roles)));
    }

    private void consumeToken(String rawToken, OneTimeToken.Purpose purpose, String newPassword, String action) {
        Instant now = Instant.now(clock);
        OneTimeToken token = oneTimeTokens.findByTokenHash(Hashing.sha256Hex(rawToken == null ? "" : rawToken))
                .filter(t -> t.getPurpose() == purpose && t.getUsedAt() == null && t.getExpiresAt().isAfter(now))
                .orElseThrow(() -> BusinessException.badRequest("LIEN_INVALIDE", "Ce lien est invalide ou a expiré"));
        UserAccount user = users.findById(token.getUserId())
                .orElseThrow(() -> BusinessException.badRequest("LIEN_INVALIDE", "Ce lien est invalide ou a expiré"));
        setPassword(user, newPassword);
        token.setUsedAt(now);
        user.setEmailVerified(true);
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        audit.recordAs(AuditCategory.SECURITE, action, "UserAccount", user.getId(), Map.of(), user.getId(), user.getEmail());
    }

    private void setPassword(UserAccount user, String newPassword) {
        passwordPolicy.validate(newPassword, user.getEmail());
        user.setPasswordHash(encoder.encode(newPassword));
        user.setPasswordChangedAt(Instant.now(clock));
        user.setTokenVersion(user.getTokenVersion() + 1);
        refreshTokens.revokeAllForUser(user.getId(), Instant.now(clock), "CHANGEMENT_MOT_DE_PASSE");
    }

    private String createOneTimeToken(Long userId, OneTimeToken.Purpose purpose, Duration ttl) {
        Instant now = Instant.now(clock);
        oneTimeTokens.invalidateAll(userId, purpose, now);
        String raw = Hashing.randomToken();
        OneTimeToken t = new OneTimeToken();
        t.setUserId(userId);
        t.setPurpose(purpose);
        t.setTokenHash(Hashing.sha256Hex(raw));
        t.setExpiresAt(now.plus(ttl));
        oneTimeTokens.save(t);
        return raw;
    }

    private record IssuedRefresh(String raw, RefreshToken entity) {
    }

    private IssuedRefresh createRefreshToken(Long userId, UUID family, String ip, String userAgent) {
        String raw = Hashing.randomToken();
        RefreshToken t = new RefreshToken();
        t.setUserId(userId);
        t.setTokenHash(Hashing.sha256Hex(raw));
        t.setFamilyId(family);
        t.setExpiresAt(Instant.now(clock).plus(props.security().refreshTokenTtl()));
        t.setIpAddress(ip);
        t.setUserAgent(userAgent);
        return new IssuedRefresh(raw, refreshTokens.save(t));
    }

    private void logDevelopmentLink(String kind, String link) {
        if (props.environment() == AppProperties.DeploymentEnvironment.DEVELOPPEMENT) {
            log.warn("[DÉVELOPPEMENT UNIQUEMENT] Lien de {} (aucun serveur SMTP requis en local) : {}", kind, link);
        }
    }

    private BusinessException invalidCredentials() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "IDENTIFIANTS_INVALIDES", INVALID_CREDENTIALS);
    }

    private BusinessException sessionExpired() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIREE", "Session expirée, veuillez vous reconnecter");
    }
}
