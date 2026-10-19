package sn.coophabitat.admin;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CoopSettingsRepository extends JpaRepository<CoopSettings, Short> {

    default CoopSettings current() {
        return findById((short) 1).orElseThrow();
    }
}
