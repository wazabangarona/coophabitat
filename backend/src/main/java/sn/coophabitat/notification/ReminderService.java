package sn.coophabitat.notification;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.coophabitat.audit.AuditCategory;
import sn.coophabitat.audit.AuditService;
import sn.coophabitat.common.Money;
import sn.coophabitat.config.AppProperties;
import sn.coophabitat.contribution.MemberObligation;
import sn.coophabitat.contribution.MemberObligationRepository;
import sn.coophabitat.member.Member;
import sn.coophabitat.member.MemberRepository;
import sn.coophabitat.reporting.PdfSupport;

import java.time.LocalDate;
import java.util.Map;

/** Rappels d'échéance : par défaut trois jours avant la date d'échéance (fuseau Africa/Dakar). */
@Service
public class ReminderService {

    private final MemberObligationRepository obligations;
    private final MemberRepository members;
    private final NotificationService notifications;
    private final AuditService audit;
    private final AppProperties props;

    public ReminderService(MemberObligationRepository obligations, MemberRepository members,
                           NotificationService notifications, AuditService audit, AppProperties props) {
        this.obligations = obligations;
        this.members = members;
        this.notifications = notifications;
        this.audit = audit;
        this.props = props;
    }

    public record ReminderRun(LocalDate runDate, LocalDate dueDate, int obligations, int notificationsCreated) {
    }

    @Transactional
    public ReminderRun run(LocalDate today) {
        int days = props.contribution().reminderDaysBefore();
        LocalDate due = today.plusDays(days);
        int created = 0;
        int count = 0;
        for (MemberObligation o : obligations.findOpenDueOn(due)) {
            Member m = members.findById(o.getMemberId()).orElse(null);
            if (m == null || !props.contribution().generateForStatuses().contains(m.getStatus().name())) {
                continue;
            }
            count++;
            String body = "Bonjour " + m.getFirstName() + ",\n\n"
                    + "Votre cotisation « " + o.getPeriodLabel() + " » arrive à échéance le "
                    + PdfSupport.DATE.format(o.getDueDate()) + ". Montant restant dû : " + Money.format(o.outstanding())
                    + ".\n\nVous pouvez la régler depuis votre espace membre CoopHabitat. "
                    + "Si vous avez déjà payé, ne tenez pas compte de ce message.\n\n"
                    + "Coopérative d'habitat — message automatique";
            created += notifications.notifyMember(m.getId(), "RAPPEL_ECHEANCE_J" + days,
                    "Rappel : échéance du " + PdfSupport.DATE.format(o.getDueDate()), body,
                    "RAPPEL_J" + days + ":" + o.getId(), "MemberObligation", o.getId());
        }
        audit.record(AuditCategory.NOTIFICATION, "RAPPELS_EXECUTES", "Reminder", today.toString(),
                Map.of("echeance", due.toString(), "echeances", count, "notifications", created));
        return new ReminderRun(today, due, count, created);
    }
}
