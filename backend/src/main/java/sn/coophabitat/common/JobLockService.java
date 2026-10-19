package sn.coophabitat.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.management.ManagementFactory;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Verrou de tâche planifiée en base : garantit qu'une seule instance exécute une tâche donnée
 * à un instant donné (équivalent minimal de ShedLock, sans dépendance supplémentaire).
 */
@Service
public class JobLockService {

    private static final String OWNER = ManagementFactory.getRuntimeMXBean().getName();

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JobLockService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryLock(String job, Duration maxDuration) {
        Instant now = Instant.now(clock);
        jdbc.update("INSERT INTO scheduled_job_lock (job_name, locked_until, locked_by) VALUES (?, ?, ?) "
                + "ON CONFLICT (job_name) DO NOTHING", job, Timestamp.from(Instant.EPOCH), OWNER);
        int updated = jdbc.update("UPDATE scheduled_job_lock SET locked_until = ?, locked_by = ? "
                        + "WHERE job_name = ? AND locked_until < ?",
                Timestamp.from(now.plus(maxDuration)), OWNER, job, Timestamp.from(now));
        return updated == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String job, String result) {
        Instant now = Instant.now(clock);
        jdbc.update("UPDATE scheduled_job_lock SET locked_until = ?, last_run_at = ?, last_result = ? WHERE job_name = ?",
                Timestamp.from(now), Timestamp.from(now), result == null ? null : result.substring(0, Math.min(1000, result.length())), job);
    }
}
