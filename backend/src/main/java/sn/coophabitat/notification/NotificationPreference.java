package sn.coophabitat.notification;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

@Entity
@Table(name = "notification_preference")
@IdClass(NotificationPreference.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class NotificationPreference {

    @Id
    private Long memberId;

    @Id
    @Enumerated(EnumType.STRING)
    private NotificationChannel channel;

    private boolean enabled;

    public record Key(Long memberId, NotificationChannel channel) implements Serializable {
        public Key() {
            this(null, null);
        }
    }
}
