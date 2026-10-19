package sn.coophabitat.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import sn.coophabitat.common.BusinessException;

import java.util.Optional;

public final class CurrentUserProvider {

    private CurrentUserProvider() {
    }

    public static Optional<CurrentUser> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof CoopAuthentication ca) {
            return Optional.of(ca.getPrincipal());
        }
        return Optional.empty();
    }

    public static CurrentUser require() {
        return current().orElseThrow(() -> new BusinessException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                "NON_AUTHENTIFIE", "Authentification requise"));
    }

    /**
     * Contrôle anti-IDOR : l'utilisateur doit être le membre concerné ou disposer de la permission globale.
     */
    public static CurrentUser requireSelfOr(Long memberId, Permission permission) {
        CurrentUser user = require();
        if (user.isMember(memberId) || user.has(permission)) {
            return user;
        }
        throw BusinessException.forbidden("Accès refusé aux données d'un autre membre");
    }

    public static Long requireMemberId() {
        CurrentUser user = require();
        if (user.memberId() == null) {
            throw BusinessException.forbidden("Ce compte n'est rattaché à aucun dossier de membre");
        }
        return user.memberId();
    }
}
