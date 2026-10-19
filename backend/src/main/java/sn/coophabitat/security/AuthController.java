package sn.coophabitat.security;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import sn.coophabitat.common.BusinessException;
import sn.coophabitat.config.AppProperties;
import sn.coophabitat.security.AuthDtos.*;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentification")
public class AuthController {

    public static final String REFRESH_COOKIE = "CH_REFRESH";
    public static final String CSRF_HEADER = "X-Requested-With";
    private static final String COOKIE_PATH = "/api/v1/auth";

    private final AuthService auth;
    private final AppProperties props;

    public AuthController(AuthService auth, AppProperties props) {
        this.auth = auth;
        this.props = props;
    }

    @PostMapping("/login")
    @Operation(summary = "Connexion : renvoie un jeton d'accès et pose un cookie de renouvellement HttpOnly")
    public TokenResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest req, HttpServletResponse res) {
        AuthService.LoginResult r = auth.login(body.email(), body.password(), ClientInfo.ip(req), ClientInfo.userAgent(req));
        setRefreshCookie(res, r.refreshToken(), props.security().refreshTokenTtl());
        return new TokenResponse(r.accessToken(), "Bearer", 0, MeResponse.of(r.user())).withTtl(this);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Renouvelle la session à partir du cookie (rotation du jeton, détection de réutilisation)")
    public TokenResponse refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String refresh,
                                 HttpServletRequest req, HttpServletResponse res) {
        requireCsrfHeader(req);
        try {
            AuthService.LoginResult r = auth.refresh(refresh, ClientInfo.ip(req), ClientInfo.userAgent(req));
            setRefreshCookie(res, r.refreshToken(), props.security().refreshTokenTtl());
            return new TokenResponse(r.accessToken(), "Bearer", 0, MeResponse.of(r.user())).withTtl(this);
        } catch (BusinessException e) {
            setRefreshCookie(res, "", Duration.ZERO);
            throw e;
        }
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refresh, HttpServletRequest req,
                       HttpServletResponse res) {
        requireCsrfHeader(req);
        auth.logout(refresh);
        setRefreshCookie(res, "", Duration.ZERO);
    }

    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Déconnecte toutes les sessions de l'utilisateur courant")
    public void logoutAll(HttpServletResponse res) {
        auth.revokeAllSessions(CurrentUserProvider.require().userId(), "DECONNEXION_GLOBALE");
        setRefreshCookie(res, "", Duration.ZERO);
    }

    @GetMapping("/me")
    public MeResponse me() {
        return MeResponse.of(CurrentUserProvider.require());
    }

    @GetMapping("/sessions")
    public List<SessionInfo> sessions() {
        return auth.activeSessions(CurrentUserProvider.require().userId()).stream()
                .map(t -> new SessionInfo(t.getId(), t.getCreatedAt().toString(), t.getExpiresAt().toString(),
                        t.getUserAgent(), t.getIpAddress()))
                .toList();
    }

    @PostMapping("/password-reset/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Demande de réinitialisation : réponse identique que le compte existe ou non")
    public void requestReset(@Valid @RequestBody PasswordResetRequest body) {
        auth.requestPasswordReset(body.email());
    }

    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmReset(@Valid @RequestBody PasswordResetConfirm body) {
        auth.confirmPasswordReset(body.token(), body.newPassword());
    }

    @PostMapping("/invitation/accept")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void acceptInvitation(@Valid @RequestBody PasswordResetConfirm body) {
        auth.acceptInvitation(body.token(), body.newPassword());
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest body, HttpServletResponse res) {
        auth.changePassword(CurrentUserProvider.require().userId(), body.currentPassword(), body.newPassword());
        setRefreshCookie(res, "", Duration.ZERO);
    }

    long ttl() {
        return props.security().accessTokenTtl().toSeconds();
    }

    private void requireCsrfHeader(HttpServletRequest req) {
        if (!"CoopHabitat".equals(req.getHeader(CSRF_HEADER))) {
            throw BusinessException.forbidden("En-tête de protection CSRF manquant");
        }
    }

    private void setRefreshCookie(HttpServletResponse res, String value, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(props.security().cookieSecure())
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
        res.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
