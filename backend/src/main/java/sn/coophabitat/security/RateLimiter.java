package sn.coophabitat.security;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limiteur de débit en mémoire à fenêtre glissante.
 * Limite connue : propre à une instance ; en déploiement multi-instances, utiliser un stockage partagé
 * (Redis) ou la limitation du proxy inverse.
 */
@Component
public class RateLimiter {

    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** @return true si la requête est autorisée. */
    public boolean tryAcquire(String key, int maxRequests, long windowMillis) {
        long now = clock.millis();
        Deque<Long> deque = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst() <= now - windowMillis) {
                deque.pollFirst();
            }
            if (deque.size() >= maxRequests) {
                return false;
            }
            deque.addLast(now);
            return true;
        }
    }

    public void reset() {
        windows.clear();
    }
}
