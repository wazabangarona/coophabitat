package sn.coophabitat.notification;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "notification")
@Getter
@Setter
@NoArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private Long memberId;

    @Enumerated(EnumType.STRING)
    private NotificationChannel channel;

    private String templateCode;
    private String recipient;
    private String subject;
    private String body;

    @Enumerated(EnumType.STRING)
    private NotificationStatus status;

    private String statusDetail;
    private String dedupKey;
    private int attempts;
    private int maxAttempts = 5;
    private Instant nextAttemptAt;
    private Instant lastAttemptAt;
    private Instant sentAt;
    private Instant deliveredAt;
    private Instant readAt;
    private String relatedType;
    private Long relatedId;
    private Instant createdAt = Instant.now();

    @Version
    private long version;
}
