package sn.coophabitat.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    boolean existsByDedupKey(String dedupKey);

    @Query("select n.id from Notification n where n.status = sn.coophabitat.notification.NotificationStatus.EN_ATTENTE "
            + "and n.channel <> sn.coophabitat.notification.NotificationChannel.IN_APP "
            + "and (n.nextAttemptAt is null or n.nextAttemptAt <= :now) order by n.id")
    List<Long> findDispatchable(Instant now, Pageable pageable);

    Page<Notification> findByUserIdAndChannelOrderByIdDesc(Long userId, NotificationChannel channel, Pageable pageable);

    long countByUserIdAndChannelAndReadAtIsNull(Long userId, NotificationChannel channel);

    @Query("select n from Notification n where (:status is null or n.status = :status) "
            + "and (:channel is null or n.channel = :channel) order by n.id desc")
    Page<Notification> search(NotificationStatus status, NotificationChannel channel, Pageable pageable);

    @Query("select n.channel, n.status, count(n) from Notification n group by n.channel, n.status")
    List<Object[]> statsByChannelAndStatus();

    List<Notification> findByRelatedTypeAndRelatedId(String relatedType, Long relatedId);

    long countByStatus(NotificationStatus status);
}
