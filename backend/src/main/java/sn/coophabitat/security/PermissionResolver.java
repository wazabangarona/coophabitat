package sn.coophabitat.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Résout les permissions effectives à partir des rôles (table role_permission, chargée en mémoire). */
@Component
public class PermissionResolver {

    private final JdbcTemplate jdbc;
    private final AtomicReference<Map<String, Set<String>>> cache = new AtomicReference<>();

    public PermissionResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Set<String> permissionsFor(Collection<String> roles) {
        Map<String, Set<String>> map = cache.get();
        if (map == null) {
            map = load();
            cache.set(map);
        }
        Set<String> result = new HashSet<>();
        for (String role : roles) {
            result.addAll(map.getOrDefault(role, Set.of()));
        }
        return result;
    }

    public Set<String> knownRoles() {
        return new HashSet<>(jdbc.queryForList("SELECT code FROM role", String.class));
    }

    private Map<String, Set<String>> load() {
        Map<String, Set<String>> map = new HashMap<>();
        jdbc.query("SELECT role_code, permission_code FROM role_permission", rs -> {
            map.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getString(2));
        });
        return map;
    }
}
