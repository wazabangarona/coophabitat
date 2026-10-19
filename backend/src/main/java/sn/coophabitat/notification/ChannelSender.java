package sn.coophabitat.notification;

/** Canal d'envoi externe (courriel, SMS). Un canal non configuré ne prétend jamais avoir envoyé. */
public interface ChannelSender {

    NotificationChannel channel();

    boolean configured();

    String unavailableReason();

    /** @throws Exception si l'envoi échoue ; un retour normal signifie « remis au fournisseur ». */
    void send(String recipient, String subject, String body) throws Exception;
}
