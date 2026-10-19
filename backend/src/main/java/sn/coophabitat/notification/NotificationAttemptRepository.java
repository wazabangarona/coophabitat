package sn.coophabitat.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationAttemptRepository extends JpaRepository<NotificationAttempt, Long> {
    List<NotificationAttempt> findByNotificationIdOrderByIdAsc(Long notificationId);
}
