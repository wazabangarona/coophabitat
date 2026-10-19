package sn.coophabitat.security;

import java.util.Set;

/** Identité authentifiée, reconstruite à chaque requête depuis la base (droits toujours à jour). */
public record CurrentUser(Long userId, String email, String displayName, Long memberId, Set<String> roles,
                          Set<String> permissions) {

    public boolean has(Permission permission) {
        return permissions.contains(permission.name());
    }

    public boolean isMember(Long id) {
        return memberId != null && memberId.equals(id);
    }
}
