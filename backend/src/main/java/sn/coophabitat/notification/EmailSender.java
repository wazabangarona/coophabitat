package sn.coophabitat.notification;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import sn.coophabitat.config.AppProperties;

@Component
public class EmailSender implements ChannelSender {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final AppProperties props;
    private final Environment env;

    public EmailSender(ObjectProvider<JavaMailSender> mailSender, AppProperties props, Environment env) {
        this.mailSender = mailSender;
        this.props = props;
        this.env = env;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public boolean configured() {
        String host = env.getProperty("spring.mail.host");
        return props.mail().enabled() && host != null && !host.isBlank() && mailSender.getIfAvailable() != null;
    }

    @Override
    public String unavailableReason() {
        return "Aucun serveur SMTP configuré : le message reste en file d'attente";
    }

    @Override
    public void send(String recipient, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(props.mail().from());
        message.setTo(recipient);
        message.setSubject(subject);
        message.setText(body);
        mailSender.getObject().send(message);
    }
}
