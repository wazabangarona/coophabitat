package sn.coophabitat.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Limitation du débit des routes sensibles (authentification, paiements, vérification publique). */

public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;

    public RateLimitFilter(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String ip = ClientInfo.ip(request);
        int max = 0;
        String bucket = null;
        // Les identifiants sont protégés par IP ici et par compte (verrouillage après échecs) dans AuthService.
        // Le plafond reste large car plusieurs membres partagent souvent la même adresse IP (NAT des opérateurs mobiles).
        if (path.equals("/api/v1/auth/login") || path.startsWith("/api/v1/auth/password-reset")
                || path.equals("/api/v1/auth/invitation/accept")) {
            bucket = "credentials";
            max = 20;
        } else if (path.startsWith("/api/v1/auth/")) {
            bucket = "session";
            max = 240;
        } else if (path.startsWith("/api/v1/public/receipts")) {
            bucket = "verify";
            max = 20;
        } else if (path.startsWith("/api/v1/payments") && "POST".equals(request.getMethod())
                && !path.startsWith("/api/v1/payments/webhooks")) {
            bucket = "pay";
            max = 20;
        }
        if (bucket != null && !limiter.tryAcquire(bucket + ":" + ip, max, 60_000)) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Retry-After", "60");
            response.getWriter().write("{\"status\":429,\"code\":\"TROP_DE_REQUETES\",\"message\":\"Trop de requêtes. Réessayez dans une minute.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
