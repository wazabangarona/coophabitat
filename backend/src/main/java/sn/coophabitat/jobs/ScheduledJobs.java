package sn.coophabitat.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sn.coophabitat.common.JobLockService;
import sn.coophabitat.contribution.ObligationService;
import sn.coophabitat.notification.NotificationService;
import sn.coophabitat.notification.ReminderService;
import sn.coophabitat.payment.PaymentService;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Tâches planifiées (fuseau Africa/Dakar). Chacune est protégée par un verrou en base : en déploiement
 * multi-instances, une seule exécution a lieu. Toutes sont idempotentes (contraintes d'unicité),
 * de sorte qu'une exécution manquée peut être relancée sans doublon.
 */
@Component
@ConditionalOnProperty(name = "coophabitat.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledJobs {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);

    private final JobLockService locks;
    private final ObligationService obligations;
    private final ReminderService reminders;
    private final NotificationService notifications;
    private final PaymentService payments;

    public ScheduledJobs(JobLockService locks, ObligationService obligations, ReminderService reminders,
                         NotificationService notifications, PaymentService payments) {
        this.locks = locks;
        this.obligations = obligations;
        this.reminders = reminders;
        this.notifications = notifications;
        this.payments = payments;
    }

    @Scheduled(cron = "${coophabitat.contribution.generation-cron}", zone = "Africa/Dakar")
    public void generateObligations() {
        run("generation-echeances", Duration.ofMinutes(30), () -> obligations.generateAll(obligations.today()).toString());
    }

    @Scheduled(cron = "${coophabitat.contribution.reminder-cron}", zone = "Africa/Dakar")
    public void sendReminders() {
        run("rappels-echeances", Duration.ofMinutes(30), () -> reminders.run(obligations.today()).toString());
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT30S")
    public void dispatchNotifications() {
        run("envoi-notifications", Duration.ofMinutes(5), () -> "traitées : " + notifications.dispatchPending(100));
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    public void reconcilePayments() {
        run("reconciliation-paiements", Duration.ofMinutes(10), () -> "examinées : " + payments.reconcileOpenIntents());
    }

    private void run(String name, Duration max, Supplier<String> task) {
        if (!locks.tryLock(name, max)) {
            return;
        }
        String result;
        try {
            result = task.get();
        } catch (RuntimeException e) {
            log.error("Tâche {} en échec", name, e);
            result = "ECHEC : " + e.getClass().getSimpleName();
        }
        locks.release(name, result);
    }
}
