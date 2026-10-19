package sn.coophabitat.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, NotificationPreference.Key> {
    List<NotificationPreference> findByMemberId(Long memberId);
}
