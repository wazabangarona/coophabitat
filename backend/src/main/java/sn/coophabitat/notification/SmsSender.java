package sn.coophabitat.notification;

import org.springframework.stereotype.Component;
import sn.coophabitat.config.AppProperties;

/**
 * Point d'extension SMS. Aucun fournisseur n'est intégré : tant qu'aucun accès n'est fourni,
 * les SMS restent en file d'attente avec leur état réel, sans prétendre avoir été envoyés.
 */
@Component
public class SmsSender implements ChannelSender {

    private final AppProperties props;

    public SmsSender(AppProperties props) {
        this.props = props;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.SMS;
    }

    @Override
    public boolean configured() {
        return false;
    }

    @Override
    public String unavailableReason() {
        String p = props.sms() == null ? null : props.sms().provider();
        return (p == null || p.isBlank() || "none".equalsIgnoreCase(p))
                ? "Aucun fournisseur SMS configuré : le message reste en file d'attente"
                : "Fournisseur SMS « " + p + " » non intégré : le message reste en file d'attente";
    }

    @Override
    public void send(String recipient, String subject, String body) {
        throw new UnsupportedOperationException(unavailableReason());
    }
}
