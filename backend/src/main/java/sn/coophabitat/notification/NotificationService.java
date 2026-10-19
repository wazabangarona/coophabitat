package sn.coophabitat.notification;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import sn.coophabitat.common.Hashing;
import sn.coophabitat.member.Member;
import sn.coophabitat.member.MemberRepository;
import sn.coophabitat.security.UserAccount;
import sn.coophabitat.security.UserAccountRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * File de notifications. Chaque message est enregistré avec son état réel :
 * EN_ATTENTE tant qu'aucun fournisseur ne l'a accepté, ENVOYE lorsqu'il a été remis au fournisseur,
 * ECHOUE après épuisement des tentatives, LIVRE uniquement sur accusé de livraison du fournisseur.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final Duration[] BACKOFF = {Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
            Duration.ofHours(2), Duration.ofHours(12)};

    private final NotificationRepository repository;
    private final NotificationAttemptRepository attempts;
    private final NotificationPreferenceRepository preferences;
    private final MemberRepository members;
    private final UserAccountRepository users;
    private final Map<NotificationChannel, ChannelSender> senders = new EnumMap<>(NotificationChannel.class);
    private final MeterRegistry metrics;
    private final Clock clock;
    private final TransactionTemplate txNew;

    public NotificationService(NotificationRepository repository, NotificationAttemptRepository attempts,
                               NotificationPreferenceRepository preferences, MemberRepository members,
                               UserAccountRepository users, List<ChannelSender> senderList, MeterRegistry metrics,
                               Clock clock, PlatformTransactionManager tm) {
        this.repository = repository;
        this.attempts = attempts;
        this.preferences = preferences;
        this.members = members;
        this.users = users;
        senderList.forEach(s -> senders.put(s.channel(), s));
        this.metrics = metrics;
        this.clock = clock;
        this.txNew = new TransactionTemplate(tm);
        this.txNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Canaux actifs d'un membre (tous activés par défaut, sauf retrait explicite). */
    public Map<NotificationChannel, Boolean> preferencesOf(Long memberId) {
        Map<NotificationChannel, Boolean> map = new EnumMap<>(NotificationChannel.class);
        for (NotificationChannel c : NotificationChannel.values()) {
            map.put(c, true);
        }
        preferences.findByMemberId(memberId).forEach(p -> map.put(p.getChannel(), p.isEnabled()));
        return map;
    }

    @Transactional
    public void setPreference(Long memberId, NotificationChannel channel, boolean enabled) {
        NotificationPreference p = preferences.findById(new NotificationPreference.Key(memberId, channel))
                .orElseGet(() -> {
                    NotificationPreference n = new NotificationPreference();
                    n.setMemberId(memberId);
                    n.setChannel(channel);
                    return n;
                });
        p.setEnabled(enabled);
        preferences.save(p);
    }

    /**
     * Notifie un membre sur ses canaux actifs. La clé {@code dedupBase} empêche tout doublon
     * (un même rappel n'est jamais créé deux fois, même si la tâche est relancée).
     * @return nombre de notifications créées
     */
    @Transactional
    public int notifyMember(Long memberId, String template, String subject, String body, String dedupBase,
                            String relatedType, Long relatedId) {
        Member member = members.findById(memberId).orElse(null);
        if (member == null) {
            return 0;
        }
        Map<NotificationChannel, Boolean> prefs = preferencesOf(memberId);
        UserAccount account = users.findByMemberId(memberId).orElse(null);
        int created = 0;
        for (NotificationChannel channel : NotificationChannel.values()) {
            if (!prefs.get(channel)) {
                continue;
            }
            String recipient = switch (channel) {
                case EMAIL -> member.getEmail();
                case SMS -> member.getPhone();
                case IN_APP -> account == null ? null : String.valueOf(account.getId());
            };
            if (recipient == null || recipient.isBlank()) {
                continue;
            }
            String key = dedupBase + ":" + channel;
            if (repository.existsByDedupKey(key)) {
                continue;
            }
            Notification n = base(channel, template, subject, body, key, relatedType, relatedId);
            n.setMemberId(memberId);
            n.setUserId(account == null ? null : account.getId());
            n.setRecipient(recipient);
            if (channel == NotificationChannel.IN_APP) {
                n.setStatus(NotificationStatus.ENVOYE);
                n.setSentAt(Instant.now(clock));
                n.setStatusDetail("Disponible dans l'espace membre");
            }
            repository.save(n);
            metrics.counter("coophabitat.notifications.created", "channel", channel.name(), "template", template).increment();
            created++;
        }
        return created;
    }

    /** Notification interne (dans l'application) destinée à un utilisateur du bureau. */
    @Transactional
    public void notifyUser(Long userId, String template, String subject, String body, String dedupKey,
                           String relatedType, Long relatedId) {
        String key = dedupKey + ":" + NotificationChannel.IN_APP;
        if (repository.existsByDedupKey(key)) {
            return;
        }
        Notification n = base(NotificationChannel.IN_APP, template, subject, body, key, relatedType, relatedId);
        n.setUserId(userId);
        n.setRecipient(String.valueOf(userId));
        n.setStatus(NotificationStatus.ENVOYE);
        n.setSentAt(Instant.now(clock));
        n.setStatusDetail("Disponible dans l'application");
        repository.save(n);
    }

    /**
     * Courriel de sécurité (lien à usage unique). Son contenu n'est jamais conservé en base :
     * envoi immédiat, sans reprise. Si aucun serveur SMTP n'est configuré, l'échec est consigné tel quel.
     */
    public void sendSecurityEmail(UserAccount user, String template, String subject, String body) {
        ChannelSender sender = senders.get(NotificationChannel.EMAIL);
        Notification n = base(NotificationChannel.EMAIL, template, subject,
                "[Contenu confidentiel non conservé : lien de sécurité à usage unique]",
                template + ":" + user.getId() + ":" + Hashing.randomCode(12), "UserAccount", user.getId());
        n.setUserId(user.getId());
        n.setMemberId(user.getMemberId());
        n.setRecipient(user.getEmail());
        n.setMaxAttempts(1);
        n.setAttempts(1);
        n.setLastAttemptAt(Instant.now(clock));
        if (sender == null || !sender.configured()) {
            n.setStatus(NotificationStatus.ECHOUE);
            n.setStatusDetail("Non envoyé : " + (sender == null ? "canal indisponible" : sender.unavailableReason()));
        } else {
            try {
                sender.send(user.getEmail(), subject, body);
                n.setStatus(NotificationStatus.ENVOYE);
                n.setSentAt(Instant.now(clock));
                n.setStatusDetail("Remis au serveur SMTP");
            } catch (Exception e) {
                n.setStatus(NotificationStatus.ECHOUE);
                n.setStatusDetail("Échec SMTP : " + e.getClass().getSimpleName());
            }
        }
        repository.save(n);
    }

    /** Traite la file des notifications en attente (appelé par la tâche planifiée). */
    public int dispatchPending(int batchSize) {
        List<Long> ids = repository.findDispatchable(Instant.now(clock), PageRequest.of(0, batchSize));
        int processed = 0;
        for (Long id : ids) {
            try {
                txNew.executeWithoutResult(s -> dispatchOne(id));
                processed++;
            } catch (RuntimeException e) {
                log.warn("Notification {} non traitée : {}", id, e.getMessage());
            }
        }
        return processed;
    }

    private void dispatchOne(Long id) {
        Notification n = repository.findById(id).orElseThrow();
        if (n.getStatus() != NotificationStatus.EN_ATTENTE) {
            return;
        }
        Instant now = Instant.now(clock);
        ChannelSender sender = senders.get(n.getChannel());
        if (sender == null || !sender.configured()) {
            // Aucun fournisseur : le message reste en attente, sans tentative fictive ; nouvel examen dans une heure.
            n.setStatusDetail(sender == null ? "Canal indisponible" : sender.unavailableReason());
            n.setNextAttemptAt(now.plus(Duration.ofHours(1)));
            return;
        }
        n.setAttempts(n.getAttempts() + 1);
        n.setLastAttemptAt(now);
        NotificationAttempt attempt = new NotificationAttempt();
        attempt.setNotificationId(n.getId());
        attempt.setAttemptedAt(now);
        try {
            sender.send(n.getRecipient(), n.getSubject(), n.getBody());
            n.setStatus(NotificationStatus.ENVOYE);
            n.setSentAt(now);
            n.setStatusDetail("Remis au fournisseur (livraison non confirmée)");
            attempt.setOutcome("ENVOYE");
            metrics.counter("coophabitat.notifications.sent", "channel", n.getChannel().name()).increment();
        } catch (Exception e) {
            String detail = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " : " + truncate(e.getMessage()));
            attempt.setOutcome("ECHEC");
            attempt.setDetail(detail);
            if (n.getAttempts() >= n.getMaxAttempts()) {
                n.setStatus(NotificationStatus.ECHOUE);
                n.setStatusDetail("Échec définitif après " + n.getAttempts() + " tentatives : " + detail);
                metrics.counter("coophabitat.notifications.failed", "channel", n.getChannel().name()).increment();
            } else {
                n.setNextAttemptAt(now.plus(BACKOFF[Math.min(n.getAttempts() - 1, BACKOFF.length - 1)]));
                n.setStatusDetail("Nouvelle tentative programmée : " + detail);
            }
        }
        attempts.save(attempt);
    }

    @Transactional
    public Notification markRead(Long notificationId, Long userId) {
        Notification n = repository.findById(notificationId)
                .filter(x -> userId.equals(x.getUserId()) && x.getChannel() == NotificationChannel.IN_APP)
                .orElseThrow(() -> sn.coophabitat.common.BusinessException.notFound("Notification"));
        if (n.getReadAt() == null) {
            n.setReadAt(Instant.now(clock));
        }
        return n;
    }

    private Notification base(NotificationChannel channel, String template, String subject, String body, String key,
                              String relatedType, Long relatedId) {
        Notification n = new Notification();
        n.setChannel(channel);
        n.setTemplateCode(template);
        n.setSubject(truncate(subject, 300));
        n.setBody(truncate(body, 4000));
        n.setStatus(NotificationStatus.EN_ATTENTE);
        n.setDedupKey(key);
        n.setRelatedType(relatedType);
        n.setRelatedId(relatedId);
        n.setCreatedAt(Instant.now(clock));
        n.setNextAttemptAt(Instant.now(clock));
        return n;
    }

    private static String truncate(String s) {
        return truncate(s, 400);
    }

    private static String truncate(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }
}
