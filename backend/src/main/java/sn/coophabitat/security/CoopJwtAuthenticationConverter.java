package sn.coophabitat.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

/**
 * Convertit un JWT valide en identité applicative en relisant le compte en base :
 * un compte désactivé, verrouillé ou dont la version de session a changé est refusé immédiatement.
 */
@Component
public class CoopJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UserAccountRepository users;
    private final PermissionResolver permissions;

    public CoopJwtAuthenticationConverter(UserAccountRepository users, PermissionResolver permissions) {
        this.users = users;
        this.permissions = permissions;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Long userId;
        try {
            userId = Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw new BadCredentialsException("Jeton invalide");
        }
        UserAccount user = users.findById(userId).orElseThrow(() -> new BadCredentialsException("Jeton invalide"));
        Object tv = jwt.getClaim("tv");
        int tokenVersion = tv instanceof Number n ? n.intValue() : -1;
        if (!user.isEnabled() || tokenVersion != user.getTokenVersion()
                || (user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now()))) {
            throw new BadCredentialsException("Session expirée ou révoquée");
        }
        Set<String> roles = Set.copyOf(user.getRoles());
        CurrentUser current = new CurrentUser(user.getId(), user.getEmail(), user.getDisplayName(), user.getMemberId(),
                roles, Set.copyOf(permissions.permissionsFor(roles)));
        return new CoopAuthentication(current, jwt);
    }
}
