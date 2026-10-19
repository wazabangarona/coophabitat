package sn.coophabitat.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CoopAuthentication extends AbstractAuthenticationToken {

    private final CurrentUser principal;
    private final transient Jwt jwt;

    public CoopAuthentication(CurrentUser principal, Jwt jwt) {
        super(Stream.concat(principal.permissions().stream(), principal.roles().stream().map(r -> "ROLE_" + r))
                .map(SimpleGrantedAuthority::new).collect(Collectors.toSet()));
        this.principal = principal;
        this.jwt = jwt;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return jwt;
    }

    @Override
    public CurrentUser getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return String.valueOf(principal.userId());
    }
}
