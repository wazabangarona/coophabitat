package sn.coophabitat.notification;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "notification_attempt")
@Getter
@Setter
@NoArgsConstructor
public class NotificationAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long notificationId;
    private Instant attemptedAt = Instant.now();
    private String outcome;
    private String detail;
}
