package sn.coophabitat.notification;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import sn.coophabitat.common.BusinessException;
import sn.coophabitat.common.PageResponse;
import sn.coophabitat.security.CurrentUserProvider;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications")
public class NotificationController {

    private final NotificationService service;
    private final NotificationRepository repository;
    private final NotificationAttemptRepository attempts;
    private final ReminderService reminders;
    private final List<ChannelSender> senders;

    public NotificationController(NotificationService service, NotificationRepository repository,
                                  NotificationAttemptRepository attempts, ReminderService reminders,
                                  List<ChannelSender> senders) {
        this.service = service;
        this.repository = repository;
        this.attempts = attempts;
        this.reminders = reminders;
        this.senders = senders;
    }

    public record InAppItem(Long id, String subject, String body, Instant createdAt, Instant readAt, String relatedType,
                            Long relatedId) {
        static InAppItem of(Notification n) {
            return new InAppItem(n.getId(), n.getSubject(), n.getBody(), n.getCreatedAt(), n.getReadAt(),
                    n.getRelatedType(), n.getRelatedId());
        }
    }

    public record QueueItem(Long id, Long memberId, NotificationChannel channel, String templateCode, String recipient,
                            String subject, NotificationStatus status, String statusDetail, int attempts,
                            Instant createdAt, Instant sentAt, Instant nextAttemptAt) {
        static QueueItem of(Notification n) {
            return new QueueItem(n.getId(), n.getMemberId(), n.getChannel(), n.getTemplateCode(), mask(n),
                    n.getSubject(), n.getStatus(), n.getStatusDetail(), n.getAttempts(), n.getCreatedAt(),
                    n.getSentAt(), n.getNextAttemptAt());
        }

        private static String mask(Notification n) {
            String r = n.getRecipient();
            if (r == null || n.getChannel() == NotificationChannel.IN_APP) {
                return r;
            }
            return r.length() <= 4 ? "****" : r.substring(0, 3) + "****" + r.substring(r.length() - 2);
        }
    }

    public record ChannelState(NotificationChannel channel, boolean configured, String message) {
    }

    @GetMapping("/inbox")
    public PageResponse<InAppItem> inbox(@RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        Long userId = CurrentUserProvider.require().userId();
        return PageResponse.of(repository.findByUserIdAndChannelOrderByIdDesc(userId, NotificationChannel.IN_APP,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50))), InAppItem::of);
    }

    @GetMapping("/inbox/unread-count")
    public Map<String, Long> unread() {
        return Map.of("count", repository.countByUserIdAndChannelAndReadAtIsNull(CurrentUserProvider.require().userId(),
                NotificationChannel.IN_APP));
    }

    @PostMapping("/inbox/{id}/read")
    public InAppItem markRead(@PathVariable Long id) {
        return InAppItem.of(service.markRead(id, CurrentUserProvider.require().userId()));
    }

    @GetMapping("/preferences")
    public Map<NotificationChannel, Boolean> preferences() {
        return service.preferencesOf(CurrentUserProvider.requireMemberId());
    }

    @PutMapping("/preferences/{channel}")
    public Map<NotificationChannel, Boolean> setPreference(@PathVariable NotificationChannel channel,
                                                           @RequestParam boolean enabled) {
        Long memberId = CurrentUserProvider.requireMemberId();
        service.setPreference(memberId, channel, enabled);
        return service.preferencesOf(memberId);
    }

    @GetMapping("/queue")
    @PreAuthorize("hasAuthority('NOTIFICATION_MANAGE')")
    @Operation(summary = "File des notifications avec leur état réel (destinataires masqués)")
    public PageResponse<QueueItem> queue(@RequestParam(required = false) NotificationStatus status,
                                         @RequestParam(required = false) NotificationChannel channel,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(repository.search(status, channel, PageRequest.of(Math.max(page, 0),
                Math.min(Math.max(size, 1), 100))), QueueItem::of);
    }

    @GetMapping("/queue/{id}/attempts")
    @PreAuthorize("hasAuthority('NOTIFICATION_MANAGE')")
    public List<NotificationAttempt> attempts(@PathVariable Long id) {
        return attempts.findByNotificationIdOrderByIdAsc(id);
    }

    @GetMapping("/channels")
    @PreAuthorize("hasAuthority('NOTIFICATION_MANAGE')")
    public List<ChannelState> channels() {
        return senders.stream().map(s -> new ChannelState(s.channel(), s.configured(),
                s.configured() ? "Configuré" : s.unavailableReason())).toList();
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAuthority('NOTIFICATION_MANAGE')")
    public List<Map<String, Object>> stats() {
        return repository.statsByChannelAndStatus().stream()
                .map(r -> Map.<String, Object>of("channel", r[0], "status", r[1], "count", r[2])).toList();
    }

    @PostMapping("/reminders/run")
    @PreAuthorize("hasAuthority('NOTIFICATION_MANAGE')")
    @Operation(summary = "Exécute les rappels d'échéance pour une date (idempotent : aucun doublon)")
    public ReminderService.ReminderRun runReminders(@RequestParam(required = false) LocalDate date) {
        LocalDate d = date == null ? LocalDate.now(sn.coophabitat.config.ClockConfig.DAKAR) : date;
        if (d.isBefore(LocalDate.now(sn.coophabitat.config.ClockConfig.DAKAR).minusDays(7))) {
            throw BusinessException.badRequest("DATE_INVALIDE", "Les rappels ne peuvent pas être rejoués au-delà de 7 jours");
        }
        return reminders.run(d);
    }

    @PostMapping("/dispatch")
    @PreAuthorize("hasAuthority('NOTIFICATION_MANAGE')")
    public Map<String, Integer> dispatch() {
        return Map.of("processed", service.dispatchPending(200));
    }
}
